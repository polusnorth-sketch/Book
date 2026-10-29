package com.example.reader

import android.net.Uri
import android.util.Base64
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

private val BLOCK_TAGS = setOf(
    "p", "div", "li", "ul", "ol", "blockquote", "pre", "section", "article", "aside",
    "header", "footer", "h1", "h2", "h3", "h4", "h5", "h6", "table", "tr", "dd", "dt",
    "br", "hr", "body", "figure", "figcaption",
)
private val SKIP_TAGS = setOf("script", "style", "head", "svg", "noscript")
private val WS = Regex("\\s+")

/** Превращает HTML-разметку в плоский список абзацев. */
private class HtmlWalker(private val out: MutableList<Block>) {
    private val sb = StringBuilder()
    private var heading = false

    fun flush() {
        val t = sb.toString().replace(WS, " ").trim()
        if (t.isNotEmpty()) out.add(Block(t, heading))
        sb.setLength(0)
    }

    fun walk(node: Node) {
        if (node is TextNode) {
            sb.append(node.text())
            return
        }
        if (node !is Element) return
        val tag = node.tagName().lowercase()
        if (tag in SKIP_TAGS) return
        val block = tag in BLOCK_TAGS
        val isHeading = tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6'
        if (block) flush()
        val prev = heading
        if (isHeading) heading = true
        for (child in node.childNodes()) walk(child)
        if (block) flush()
        heading = prev
    }
}

private fun htmlToBlocks(root: Element, out: MutableList<Block>) {
    val w = HtmlWalker(out)
    w.walk(root)
    w.flush()
}

private fun decodeText(b: ByteArray): String {
    if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) {
        return String(b, 3, b.size - 3, Charsets.UTF_8)
    }
    if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) {
        return String(b, 2, b.size - 2, Charsets.UTF_16LE)
    }
    if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) {
        return String(b, 2, b.size - 2, Charsets.UTF_16BE)
    }
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b))
            .toString()
    } catch (e: CharacterCodingException) {
        // Старые русские книги часто в windows-1251
        String(b, Charset.forName("windows-1251"))
    }
}

private fun resolvePath(dir: String, href: String): String {
    val clean = Uri.decode(href.substringBefore('#'))
    val full = if (dir.isEmpty()) clean else "$dir/$clean"
    val parts = ArrayList<String>()
    for (p in full.split('/')) {
        if (p.isEmpty() || p == ".") continue
        if (p == "..") {
            if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
        } else {
            parts.add(p)
        }
    }
    return parts.joinToString("/")
}

private fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        if (a[i].isDigit() && b[j].isDigit()) {
            var ie = i
            while (ie < a.length && a[ie].isDigit()) ie++
            var je = j
            while (je < b.length && b[je].isDigit()) je++
            val na = a.substring(i, ie).trimStart('0')
            val nb = b.substring(j, je).trimStart('0')
            if (na.length != nb.length) return na.length - nb.length
            val c = na.compareTo(nb)
            if (c != 0) return c
            i = ie
            j = je
        } else {
            if (a[i] != b[j]) return a[i].compareTo(b[j])
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}

private class ManifestItem(val href: String, val type: String, val props: String)

private fun fb2Walk(el: Element, out: MutableList<Block>) {
    for (c in el.children()) {
        when (c.tagName()) {
            "title", "subtitle" -> {
                val t = c.text().trim()
                if (t.isNotEmpty()) out.add(Block(t, true))
            }
            "p", "v", "text-author", "th", "td" -> {
                val t = c.text().trim()
                if (t.isNotEmpty()) out.add(Block(t, false))
            }
            "empty-line", "image", "binary" -> {}
            else -> fb2Walk(c, out)
        }
    }
}

object Parsers {

    fun formatOf(name: String): Format? {
        val n = name.lowercase()
        return when {
            n.endsWith(".epub") -> Format.EPUB
            n.endsWith(".fb2") || n.endsWith(".fb2.zip") -> Format.FB2
            n.endsWith(".txt") -> Format.TXT
            n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".xhtml") -> Format.HTML
            n.endsWith(".docx") -> Format.DOCX
            n.endsWith(".pdf") -> Format.PDF
            n.endsWith(".cbz") || n.endsWith(".zip") -> Format.CBZ
            else -> null
        }
    }

    fun parseText(file: File, format: Format, fallback: String): ParsedText = when (format) {
        Format.EPUB -> parseEpub(file, fallback)
        Format.FB2 -> parseFb2(file, fallback)
        Format.TXT -> parseTxt(file, fallback)
        Format.HTML -> parseHtml(file, fallback)
        Format.DOCX -> parseDocx(file, fallback)
        else -> throw IllegalArgumentException("Это не текстовый формат")
    }

    fun comicNames(zip: ZipFile): List<String> =
        zip.entries().asSequence()
            .filter { !it.isDirectory && isImage(it.name) }
            .map { it.name }
            .sortedWith(Comparator<String> { a, b -> naturalCompare(a.lowercase(), b.lowercase()) })
            .toList()

    private fun isImage(name: String): Boolean {
        val n = name.lowercase()
        if (n.contains("__macosx")) return false
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") ||
            n.endsWith(".webp") || n.endsWith(".gif") || n.endsWith(".bmp")
    }

    private fun buildToc(blocks: List<Block>, starts: List<Int>): List<TocItem> {
        val t = ArrayList<TocItem>()
        for (i in blocks.indices) {
            if (blocks[i].heading) t.add(TocItem(blocks[i].text.take(80), i))
        }
        if (t.isEmpty() && starts.size > 1) {
            for (n in starts.indices) t.add(TocItem("Часть ${n + 1}", starts[n]))
        }
        return t
    }

    private fun parseTxt(file: File, fallback: String): ParsedText {
        val lines = decodeText(file.readBytes()).lines()
        val blank = lines.count { it.isBlank() }
        // Если в тексте есть пустые строки между абзацами, склеиваем строки внутри абзаца.
        // Если нет, каждая строка считается отдельным абзацем.
        val joinMode = blank > (lines.size - blank) / 4
        val head = Regex("^(глава|часть|chapter|part|пролог|эпилог|prologue|epilogue)(\\s.*|[.:]?)$")
        val blocks = ArrayList<Block>()
        val sb = StringBuilder()

        fun flush() {
            val t = sb.toString().trim()
            if (t.isNotEmpty()) blocks.add(Block(t, t.length <= 70 && head.matches(t.lowercase())))
            sb.setLength(0)
        }

        for (line in lines) {
            val l = line.trim()
            if (l.isEmpty()) {
                flush()
            } else if (joinMode) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(l)
            } else {
                sb.append(l)
                flush()
            }
        }
        flush()
        return ParsedText(fallback, "", blocks, buildToc(blocks, emptyList()), null)
    }

    private fun parseHtml(file: File, fallback: String): ParsedText {
        val doc = Jsoup.parse(decodeText(file.readBytes()))
        val blocks = ArrayList<Block>()
        htmlToBlocks(doc.body(), blocks)
        val title = doc.title().trim().ifEmpty { fallback }
        return ParsedText(title, "", blocks, buildToc(blocks, emptyList()), null)
    }

    private fun parseDocx(file: File, fallback: String): ParsedText {
        return ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: throw IllegalStateException("Это не файл DOCX")
            val xml = zip.getInputStream(entry).use { it.readBytes() }
            val doc = Jsoup.parse(String(xml, Charsets.UTF_8), "", Parser.xmlParser())
            val blocks = ArrayList<Block>()
            for (p in doc.getElementsByTag("w:p")) {
                val t = p.getElementsByTag("w:t").joinToString("") { it.wholeText() }.trim()
                if (t.isEmpty()) continue
                val style = p.getElementsByTag("w:pstyle").firstOrNull()?.attr("w:val").orEmpty()
                val heading = style.startsWith("Heading", ignoreCase = true) || style.equals("Title", ignoreCase = true)
                blocks.add(Block(t, heading))
            }
            if (blocks.isEmpty()) throw IllegalStateException("Документ пуст")
            ParsedText(fallback, "", blocks, buildToc(blocks, emptyList()), null)
        }
    }

    private fun parseFb2(file: File, fallback: String): ParsedText {
        val doc = file.inputStream().use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
        val info = doc.selectFirst("title-info")
        val title = info?.selectFirst("book-title")?.text()?.trim().orEmpty().ifEmpty { fallback }
        val author = info?.selectFirst("author")?.let { a ->
            listOf("first-name", "middle-name", "last-name")
                .mapNotNull { n -> a.selectFirst(n)?.text()?.trim()?.takeIf { s -> s.isNotEmpty() } }
                .joinToString(" ")
        }.orEmpty()

        val blocks = ArrayList<Block>()
        for (body in doc.select("body")) {
            if (body.attr("name") == "notes") continue
            fb2Walk(body, blocks)
        }
        if (blocks.isEmpty()) throw IllegalStateException("Книга пуста")

        var cover: ByteArray? = null
        try {
            val href = doc.selectFirst("coverpage image")
                ?.attributes()?.firstOrNull { it.key.endsWith("href") }?.value
            if (href != null) {
                val id = href.removePrefix("#")
                val bin = doc.select("binary").firstOrNull { it.attr("id") == id }
                if (bin != null) cover = Base64.decode(bin.text(), Base64.DEFAULT)
            }
        } catch (e: Exception) {
            cover = null
        }
        return ParsedText(title, author, blocks, buildToc(blocks, emptyList()), cover)
    }

    private fun parseEpub(file: File, fallback: String): ParsedText {
        return ZipFile(file).use { zip ->
            fun read(path: String): ByteArray? {
                val e = zip.getEntry(path) ?: return null
                return zip.getInputStream(e).use { it.readBytes() }
            }

            val container = read("META-INF/container.xml") ?: throw IllegalStateException("Не найден container.xml")
            val opfPath = Jsoup.parse(String(container, Charsets.UTF_8), "", Parser.xmlParser())
                .selectFirst("rootfile")?.attr("full-path")
                ?: throw IllegalStateException("Не найден файл описания книги")
            val opfDir = opfPath.substringBeforeLast('/', "")
            val opfBytes = read(opfPath) ?: throw IllegalStateException("Не найден файл описания книги")
            val opf = Jsoup.parse(String(opfBytes, Charsets.UTF_8), "", Parser.xmlParser())

            val items = HashMap<String, ManifestItem>()
            for (el in opf.select("manifest > item")) {
                items[el.attr("id")] = ManifestItem(el.attr("href"), el.attr("media-type"), el.attr("properties"))
            }

            val title = opf.getElementsByTag("dc:title").firstOrNull()?.text()?.trim().orEmpty().ifEmpty { fallback }
            val author = opf.getElementsByTag("dc:creator").joinToString(", ") { it.text().trim() }

            var cover: ByteArray? = null
            val coverId = opf.select("metadata > meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")
            val coverItem = items.values.firstOrNull { it.props.contains("cover-image") }
                ?: coverId?.let { items[it] }
                ?: items.entries.firstOrNull { it.key.contains("cover", true) && it.value.type.startsWith("image") }?.value
            if (coverItem != null) cover = read(resolvePath(opfDir, coverItem.href))

            val blocks = ArrayList<Block>()
            val starts = ArrayList<Int>()
            for (el in opf.select("spine > itemref")) {
                val item = items[el.attr("idref")] ?: continue
                if (!item.type.contains("html")) continue
                val data = read(resolvePath(opfDir, item.href)) ?: continue
                val page = Jsoup.parse(String(data, Charsets.UTF_8))
                starts.add(blocks.size)
                htmlToBlocks(page.body(), blocks)
            }
            if (blocks.isEmpty()) throw IllegalStateException("Книга пуста")
            ParsedText(title, author, blocks, buildToc(blocks, starts), cover)
        }
    }
}
