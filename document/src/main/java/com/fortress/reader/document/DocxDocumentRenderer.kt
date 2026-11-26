package com.fortress.reader.document

import android.util.Xml
import com.fortress.reader.security.UserFacingException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/**
 * Minimal, dependency-free DOCX text extractor (paragraphs, headings, tabs, line breaks, table cells).
 * Hardened against zip bombs. Macros, embedded objects, external links and images are ignored.
 */
class DocxDocumentRenderer : DocumentRenderer {
    override val formats = setOf(DocumentFormat.DOCX)

    override suspend fun open(file: File, format: DocumentFormat): OpenedDocument = withContext(Dispatchers.IO) {
        try {
            OpenedDocument.Text(DocxParser.parse(file))
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { throw UserFacingException("The DOCX file is damaged or unsupported", e) }
    }
}

internal object DocxParser {
    private const val MAX_ENTRIES = 10_000
    private const val MAX_XML_BYTES = 50L * 1024 * 1024
    private const val MAX_BLOCKS = 200_000

    fun parse(file: File): List<TextBlock> = ZipFile(file).use { zip ->
        if (zip.size() > MAX_ENTRIES) throw IOException("Too many entries")
        val entry = zip.getEntry("word/document.xml") ?: throw IOException("Not a DOCX document")
        if (entry.size > MAX_XML_BYTES) throw IOException("Document too large")
        LimitedStream(zip.getInputStream(entry), MAX_XML_BYTES).use { parseXml(it) }
    }

    private fun parseXml(input: InputStream): List<TextBlock> {
        val p = Xml.newPullParser() // DOCTYPE/entities are not processed
        p.setInput(input, null)
        val blocks = ArrayList<TextBlock>()
        val sb = StringBuilder()
        var style = TextStyle.BODY
        var inText = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name.substringAfter(':')) {
                    "p" -> { sb.setLength(0); style = TextStyle.BODY }
                    "pStyle" -> style = styleFor(attr(p, "val"))
                    "t" -> inText = true
                    "tab" -> sb.append('\t')
                    "br", "cr" -> sb.append('\n')
                    "Fallback" -> skipElement(p) // mc:AlternateContent duplicates text-box content
                }
                XmlPullParser.TEXT -> if (inText) sb.append(p.text)
                XmlPullParser.END_TAG -> when (p.name.substringAfter(':')) {
                    "t" -> inText = false
                    "p" -> {
                        if (sb.isNotBlank()) blocks += TextBlock(sb.toString(), style)
                        if (blocks.size > MAX_BLOCKS) throw IOException("Document too large")
                        sb.setLength(0)
                    }
                }
            }
            ev = p.next()
        }
        return blocks
    }

    private fun attr(p: XmlPullParser, local: String): String? {
        for (i in 0 until p.attributeCount) if (p.getAttributeName(i).substringAfter(':') == local) return p.getAttributeValue(i)
        return null
    }

    private fun styleFor(id: String?): TextStyle {
        val s = id?.lowercase() ?: return TextStyle.BODY
        return when {
            s == "title" || s == "heading1" -> TextStyle.H1
            s == "heading2" -> TextStyle.H2
            s.startsWith("heading") -> TextStyle.H3
            else -> TextStyle.BODY
        }
    }
}
