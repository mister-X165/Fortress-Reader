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
 * FictionBook 2 (.fb2 and .fb2.zip). Text only: title, author, sections, titles, paragraphs, poems, notes.
 * Embedded images (<binary>) are skipped. Declared XML encoding (e.g. windows-1251) is honoured.
 */
class Fb2DocumentRenderer : DocumentRenderer {
    override val formats = setOf(DocumentFormat.FB2, DocumentFormat.FB2_ZIP)

    override suspend fun open(file: File, format: DocumentFormat): OpenedDocument = withContext(Dispatchers.IO) {
        try {
            OpenedDocument.Text(Fb2Parser.parse(file, format))
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { throw UserFacingException("The FB2 file is damaged or unsupported", e) }
    }
}

internal object Fb2Parser {
    private const val MAX_BYTES = 50L * 1024 * 1024
    private const val MAX_BLOCKS = 500_000
    private val PARA = setOf("p", "subtitle", "v", "text-author", "th", "td")
    private val NAME_PARTS = setOf("first-name", "middle-name", "last-name", "nickname")
    private val WS = Regex("\\s+")

    fun parse(file: File, format: DocumentFormat): List<TextBlock> =
        if (format == DocumentFormat.FB2_ZIP) ZipFile(file).use { z ->
            val e = z.entries().asSequence().firstOrNull { !it.isDirectory && it.name.endsWith(".fb2", ignoreCase = true) }
                ?: throw IOException("No FB2 file in archive")
            if (e.size > MAX_BYTES) throw IOException("Document too large")
            LimitedStream(z.getInputStream(e), MAX_BYTES).use { parseXml(it) }
        } else LimitedStream(file.inputStream().buffered(), MAX_BYTES).use { parseXml(it) }

    private fun parseXml(input: InputStream): List<TextBlock> {
        val p = Xml.newPullParser()
        p.setInput(input, null) // encoding taken from the XML declaration
        val blocks = ArrayList<TextBlock>()
        val sb = StringBuilder()
        val path = ArrayList<String>()
        val authors = ArrayList<String>()
        val nameParts = ArrayList<String>()
        var bookTitle = ""
        var infoDone = false
        var inBody = false
        var sectionDepth = 0
        var cap = 0 // 0 none, 1 paragraph, 2 book-title, 3 author name part
        var style = TextStyle.BODY

        fun emit(text: String, st: TextStyle) {
            if (text.isEmpty()) return
            blocks += TextBlock(text, st)
            if (blocks.size > MAX_BLOCKS) throw IOException("Document too large")
        }
        fun norm() = sb.toString().replace(WS, " ").trim()

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val name = p.name.substringAfter(':')
                    if (name == "binary") {
                        skipElement(p) // base64 images
                    } else {
                        when {
                            name == "body" -> {
                                inBody = true
                                if (attr(p, "name") == "notes") emit("Notes", TextStyle.H1)
                            }
                            name == "section" -> sectionDepth++
                            name in PARA && inBody -> {
                                sb.setLength(0); cap = 1
                                style = when {
                                    path.contains("title") -> headingFor(sectionDepth)
                                    name == "subtitle" -> TextStyle.H3
                                    else -> TextStyle.BODY
                                }
                            }
                            name == "book-title" && !infoDone && path.contains("title-info") -> { sb.setLength(0); cap = 2 }
                            name in NAME_PARTS && !infoDone && path.contains("title-info") && path.contains("author") -> { sb.setLength(0); cap = 3 }
                        }
                        path.add(name)
                    }
                }
                XmlPullParser.TEXT -> if (cap != 0) sb.append(p.text)
                XmlPullParser.END_TAG -> {
                    val name = p.name.substringAfter(':')
                    if (path.isNotEmpty()) path.removeAt(path.lastIndex)
                    when {
                        name in PARA && cap == 1 -> { emit(norm(), style); cap = 0 }
                        name == "book-title" && cap == 2 -> { bookTitle = norm(); cap = 0 }
                        name in NAME_PARTS && cap == 3 -> { val s = norm(); if (s.isNotEmpty()) nameParts += s; cap = 0 }
                        name == "author" && !infoDone && path.contains("title-info") -> {
                            if (nameParts.isNotEmpty()) authors += nameParts.joinToString(" ")
                            nameParts.clear()
                        }
                        name == "title-info" && !infoDone -> {
                            infoDone = true
                            emit(bookTitle, TextStyle.H1)
                            if (authors.isNotEmpty()) emit(authors.joinToString(", "), TextStyle.BODY)
                        }
                        name == "section" -> sectionDepth--
                        name == "body" -> inBody = false
                    }
                }
            }
            ev = p.next()
        }
        return blocks
    }

    private fun headingFor(depth: Int) = when (depth) { 0, 1 -> TextStyle.H1; 2 -> TextStyle.H2; else -> TextStyle.H3 }

    private fun attr(p: XmlPullParser, local: String): String? {
        for (i in 0 until p.attributeCount) if (p.getAttributeName(i).substringAfter(':') == local) return p.getAttributeValue(i)
        return null
    }
}
