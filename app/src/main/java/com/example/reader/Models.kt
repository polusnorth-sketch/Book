package com.example.reader

import org.json.JSONArray
import org.json.JSONObject

enum class Format {
    EPUB, FB2, TXT, HTML, DOCX, PDF, CBZ;

    val isPaged: Boolean get() = this == PDF || this == CBZ
}

data class Bookmark(val pos: Int, val label: String)

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val format: Format,
    val pos: Int = 0,
    val total: Int = 1,
    val bookmarks: List<Bookmark> = emptyList(),
)

/** Абзац текста книги. heading = true для заголовков глав. */
data class Block(val text: String, val heading: Boolean)

data class TocItem(val title: String, val index: Int)

class ParsedText(
    val title: String,
    val author: String,
    val blocks: List<Block>,
    val toc: List<TocItem>,
    val cover: ByteArray?,
)

data class ReaderSettings(
    val fontSize: Int = 18,
    val lineHeight: Float = 1.5f,
    val theme: Int = 0, // 0 светлая, 1 сепия, 2 тёмная
    val serif: Boolean = true,
    val zoom: Float = 1f,
)

fun Book.toJson(): JSONObject {
    val o = JSONObject()
    o.put("id", id)
    o.put("title", title)
    o.put("author", author)
    o.put("format", format.name)
    o.put("pos", pos)
    o.put("total", total)
    val arr = JSONArray()
    for (b in bookmarks) {
        arr.put(JSONObject().put("pos", b.pos).put("label", b.label))
    }
    o.put("bookmarks", arr)
    return o
}

fun bookFromJson(o: JSONObject): Book {
    val marks = ArrayList<Bookmark>()
    val arr = o.optJSONArray("bookmarks")
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            marks.add(Bookmark(m.optInt("pos"), m.optString("label")))
        }
    }
    return Book(
        id = o.getString("id"),
        title = o.optString("title"),
        author = o.optString("author"),
        format = Format.valueOf(o.getString("format")),
        pos = o.optInt("pos"),
        total = o.optInt("total", 1),
        bookmarks = marks,
    )
}
