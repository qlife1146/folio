package com.mccal.folio

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.Writer
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.coroutines.CoroutineContext
import kotlin.math.max

/** Bundled OCR and PDF parsing: no remote inference or document upload. Used only on the indexing IO thread. */
internal class ContentTextExtractor(private val context: Context) : Closeable {
    private val recognizer by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }
    private var usedOcr = false
    data class Text(val body: String, val partial: Boolean = false)

    fun extract(uri: Uri, name: String, mime: String, coroutine: CoroutineContext): Text {
        coroutine.ensureActive()
        return when {
            mime.startsWith("image/") -> photo(uri, coroutine)
            mime == "application/pdf" || name.endsWith(".pdf", true) -> pdf(uri, coroutine)
            else -> plain(uri)
        }
    }

    private fun plain(uri: Uri): Text {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= MAX_TEXT_BYTES) {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_TEXT_BYTES + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
            ?: throw IOException("Unavailable document")
        val length = minOf(bytes.size, MAX_TEXT_BYTES)
        val utf16 = bytes.size >= 2 && ((bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) ||
            (bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()))
        val text = if (utf16) String(bytes, 0, length, Charsets.UTF_16) else {
            try {
                val decoded = java.nio.CharBuffer.allocate(length)
                val result = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, length), decoded, bytes.size <= MAX_TEXT_BYTES)
                if (result.isError) result.throwException()
                decoded.flip()
                decoded.toString()
            } catch (_: java.nio.charset.CharacterCodingException) {
                // Common encoding of older Korean text files.
                String(bytes, 0, length, charset("MS949"))
            }
        }
        return Text(text.removePrefix("\uFEFF").take(MAX_CHARS), bytes.size > MAX_TEXT_BYTES || text.length > MAX_CHARS)
    }

    private fun photo(uri: Uri, coroutine: CoroutineContext): Text {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val scale = minOf(1f, 2560f / max(info.size.width, info.size.height))
            decoder.setTargetSize(max(1, (info.size.width * scale).toInt()), max(1, (info.size.height * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            coroutine.ensureActive()
            usedOcr = true
            // Wait on the IO worker so the bitmap stays alive until ML Kit has finished using it.
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).text
            coroutine.ensureActive()
            return Text(text.take(MAX_CHARS), text.length > MAX_CHARS)
        } finally { bitmap.recycle() }
    }

    private fun pdf(uri: Uri, coroutine: CoroutineContext): Text {
        PDFBoxResourceLoader.init(context)
        val file = File.createTempFile("search-pdf-", ".pdf", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        coroutine.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 64L * 1024 * 1024) throw IOException("PDF exceeds indexing limit")
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IOException("Unavailable document")
            return PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use { document ->
                if (!document.currentAccessPermission.canExtractContent()) throw IOException("Text extraction is restricted")
                val body = StringBuilder()
                val writer = object : Writer() {
                    override fun write(chars: CharArray, offset: Int, length: Int) {
                        coroutine.ensureActive()
                        val available = MAX_CHARS - body.length
                        body.append(chars, offset, minOf(length, available))
                        if (length > available) throw TextLimit()
                    }
                    override fun flush() = Unit
                    override fun close() = Unit
                }
                var partial = false
                try { PDFTextStripper().writeText(document, writer) }
                catch (_: TextLimit) { partial = true }
                Text(body.toString(), partial)
            }
        } finally { file.delete() }
    }

    override fun close() { if (usedOcr) recognizer.close() }
    private class TextLimit : IOException()
    companion object {
        private const val MAX_CHARS = 200_000
        private const val MAX_TEXT_BYTES = 1024 * 1024
        fun supports(name: String, mime: String) = mime == "text/plain" || mime == "application/pdf" ||
            name.endsWith(".txt", true) || name.endsWith(".pdf", true)
    }
}
