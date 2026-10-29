package com.example.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class Library(private val ctx: Context) {
    private val booksDir = File(ctx.filesDir, "books").apply { mkdirs() }
    private val coversDir = File(ctx.filesDir, "covers").apply { mkdirs() }
    private val indexFile = File(ctx.filesDir, "library.json")

    fun bookFile(b: Book): File = File(booksDir, b.id + "." + b.format.name.lowercase())

    fun coverFile(id: String): File = File(coversDir, "$id.jpg")

    fun load(): List<Book> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(indexFile.readText())
            val list = ArrayList<Book>()
            for (i in 0 until arr.length()) list.add(bookFromJson(arr.getJSONObject(i)))
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(list: List<Book>) {
        val arr = JSONArray()
        for (b in list) arr.put(b.toJson())
        val tmp = File(indexFile.path + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(indexFile)
    }

    fun delete(b: Book) {
        bookFile(b).delete()
        coverFile(b.id).delete()
    }

    private fun displayName(uri: Uri): String {
        val cursor = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        if (cursor != null) {
            cursor.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) {
                        val n = c.getString(i)
                        if (n != null) return n
                    }
                }
            }
        }
        return uri.lastPathSegment ?: "book"
    }

    private fun saveCover(id: String, data: ByteArray) {
        val bmp = Images.decodeSampled(data, 400) ?: return
        coverFile(id).writeBytes(Images.toJpeg(bmp))
    }

    /** Копирует файл в хранилище приложения и читает название, автора и обложку. */
    suspend fun import(uri: Uri): Book = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val format = Parsers.formatOf(name)
            ?: throw IllegalArgumentException("Формат не поддерживается: $name")
        val id = UUID.randomUUID().toString()
        val target = File(booksDir, "$id.${format.name.lowercase()}")
        try {
            val input = ctx.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Не удалось открыть файл $name")
            input.use { ins ->
                if (name.lowercase().endsWith(".fb2.zip")) {
                    ZipInputStream(ins).use { zin ->
                        var e: ZipEntry? = zin.nextEntry
                        while (e != null && !e.name.lowercase().endsWith(".fb2")) e = zin.nextEntry
                        if (e == null) throw IllegalStateException("В архиве нет файла .fb2")
                        target.outputStream().use { out -> zin.copyTo(out) }
                    }
                } else {
                    target.outputStream().use { out -> ins.copyTo(out) }
                }
            }

            val base = name.substringBeforeLast('.').removeSuffix(".fb2")
            var title = base
            var author = ""
            var total = 1
            var cover: ByteArray? = null
            when (format) {
                Format.PDF -> {
                    val info = PdfSource.info(target)
                    total = info.first
                    cover = info.second
                }
                Format.CBZ -> {
                    val info = ComicSource.info(target)
                    total = info.first
                    cover = info.second
                }
                else -> {
                    val p = Parsers.parseText(target, format, base)
                    title = p.title
                    author = p.author
                    total = p.blocks.size.coerceAtLeast(1)
                    cover = p.cover
                }
            }
            val coverBytes = cover
            if (coverBytes != null) saveCover(id, coverBytes)
            Book(id = id, title = title, author = author, format = format, total = total)
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
    }
}
