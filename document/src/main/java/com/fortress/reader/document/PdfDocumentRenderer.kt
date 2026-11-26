package com.fortress.reader.document

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.fortress.reader.security.UserFacingException
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PdfDocumentRenderer : DocumentRenderer {
    override val formats = setOf(DocumentFormat.PDF)

    override suspend fun open(file: File, format: DocumentFormat): OpenedDocument = withContext(Dispatchers.IO) {
        var pfd: ParcelFileDescriptor? = null
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfPages(pfd, PdfRenderer(pfd))
        } catch (e: SecurityException) {
            pfd?.close()
            throw UserFacingException("Password-protected PDFs are not supported yet", e)
        } catch (e: IOException) {
            pfd?.close()
            throw UserFacingException("The PDF file is damaged or unsupported", e)
        } catch (e: Throwable) {
            pfd?.close(); throw e
        }
    }
}

private class PdfPages(private val pfd: ParcelFileDescriptor, private val renderer: PdfRenderer) : OpenedDocument.Pages() {
    private val mutex = Mutex() // PdfRenderer allows one open page at a time
    @Volatile private var closed = false
    override val pageCount = renderer.pageCount

    override suspend fun render(index: Int, targetWidthPx: Int): Bitmap = mutex.withLock {
        withContext(Dispatchers.Default) {
            check(!closed) { "Document closed" }
            renderer.openPage(index).use { page ->
                val w = targetWidthPx.coerceIn(1, 4096)
                val h = (page.height * (w.toFloat() / page.width)).toInt().coerceIn(1, 16384)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }

    override fun close() {
        closed = true
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }
}
