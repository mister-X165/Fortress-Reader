package com.fortress.reader.document

import android.graphics.Bitmap
import com.fortress.reader.security.UserFacingException
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class TextStyle { BODY, H1, H2, H3 }
data class TextBlock(val text: String, val style: TextStyle = TextStyle.BODY)

/** Result of opening a file. Close it to release file descriptors. */
sealed interface OpenedDocument : Closeable {
    /** Page-based documents (PDF). */
    abstract class Pages : OpenedDocument {
        abstract val pageCount: Int
        abstract suspend fun render(index: Int, targetWidthPx: Int): Bitmap
    }
    /** Reflowable documents (DOCX, FB2). */
    class Text(val blocks: List<TextBlock>) : OpenedDocument { override fun close() {} }
}

/** Extension point: add another format by implementing this and registering it. */
interface DocumentRenderer {
    val formats: Set<DocumentFormat>
    suspend fun open(file: File, format: DocumentFormat): OpenedDocument
}

class RendererRegistry(private val renderers: List<DocumentRenderer>) {
    /** Detects the real format from file content, then delegates. Throws [UserFacingException]. */
    suspend fun open(file: File): OpenedDocument {
        val format = withContext(Dispatchers.IO) { DocumentFormat.sniff(file) }
            ?: throw UserFacingException("Unsupported or damaged file. Supported formats: ${DocumentFormat.SUPPORTED_LABEL}")
        val renderer = renderers.firstOrNull { format in it.formats }
            ?: throw UserFacingException("Unsupported format")
        return renderer.open(file, format)
    }

    companion object {
        fun default() = RendererRegistry(
            listOf(PdfDocumentRenderer(), DocxDocumentRenderer(), Fb2DocumentRenderer())
        )
    }
}
