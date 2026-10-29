package com.example.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

object Images {
    /** Декодирует картинку так, чтобы её ширина была не меньше minWidth (экономит память). */
    fun decodeSampled(data: ByteArray, minWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= minWidth) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        return BitmapFactory.decodeByteArray(data, 0, data.size, opts)
    }

    fun toJpeg(bmp: Bitmap): ByteArray {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        return bos.toByteArray()
    }
}

/** Общий интерфейс для книг, которые показываются постранично как картинки (PDF, CBZ). */
interface PageSource {
    val count: Int
    fun render(index: Int, widthPx: Int): Bitmap?
    fun close()
}

class BitmapCache {
    private val lru = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun get(key: String): Bitmap? = lru.get(key)

    fun put(key: String, bmp: Bitmap) {
        lru.put(key, bmp)
    }
}

class PdfSource(file: File) : PageSource {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private val cache = BitmapCache()
    override val count: Int = renderer.pageCount

    @Synchronized
    override fun render(index: Int, widthPx: Int): Bitmap? {
        val key = "$index:$widthPx"
        val cached = cache.get(key)
        if (cached != null) return cached
        val page = renderer.openPage(index)
        try {
            val h = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            cache.put(key, bmp)
            return bmp
        } finally {
            page.close()
        }
    }

    @Synchronized
    override fun close() {
        try {
            renderer.close()
        } catch (e: Exception) {
        }
        try {
            fd.close()
        } catch (e: Exception) {
        }
    }

    companion object {
        /** Число страниц и обложка (первая страница) для библиотеки. */
        fun info(file: File): Pair<Int, ByteArray?> {
            val src = PdfSource(file)
            try {
                val cover = src.render(0, 400)?.let { Images.toJpeg(it) }
                return Pair(src.count, cover)
            } finally {
                src.close()
            }
        }
    }
}

class ComicSource(file: File) : PageSource {
    private val zip = ZipFile(file)
    private val names = Parsers.comicNames(zip)
    private val cache = BitmapCache()
    override val count: Int = names.size

    @Synchronized
    override fun render(index: Int, widthPx: Int): Bitmap? {
        val key = "$index:$widthPx"
        val cached = cache.get(key)
        if (cached != null) return cached
        val entry = zip.getEntry(names[index]) ?: return null
        val data = zip.getInputStream(entry).use { it.readBytes() }
        val bmp = Images.decodeSampled(data, widthPx) ?: return null
        cache.put(key, bmp)
        return bmp
    }

    @Synchronized
    override fun close() {
        try {
            zip.close()
        } catch (e: Exception) {
        }
    }

    companion object {
        fun info(file: File): Pair<Int, ByteArray?> {
            return ZipFile(file).use { z ->
                val names = Parsers.comicNames(z)
                if (names.isEmpty()) throw IllegalStateException("В архиве нет изображений")
                val data = z.getInputStream(z.getEntry(names[0])).use { it.readBytes() }
                Pair(names.size, data as ByteArray?)
            }
        }
    }
}
