package com.fortress.reader.document

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/** Content-based format detection: file names / MIME types from pickers are unreliable. */
enum class DocumentFormat {
    PDF, DOCX, FB2, FB2_ZIP;

    companion object {
        const val SUPPORTED_LABEL = "PDF, DOCX, FB2 (also .fb2.zip)"
        private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)

        /** Cheap header check (first <=1 KiB) used before encrypting an import. */
        fun looksSupported(header: ByteArray, length: Int = header.size): Boolean {
            val n = minOf(length, header.size)
            if (n <= 0) return false
            if (indexOf(header, minOf(n, 1024), PDF_MAGIC) >= 0) return true
            if (isZip(header, n)) return true
            val i = firstContentByte(header, n)
            return i >= 0 && header[i] == '<'.code.toByte()
        }

        /** Best-effort human-readable container label for the import confirmation screen. */
        fun labelFor(header: ByteArray, length: Int): String {
            val n = minOf(length, header.size)
            if (n <= 0) return "UNKNOWN"
            if (indexOf(header, minOf(n, 1024), PDF_MAGIC) >= 0) return "PDF"
            if (isZip(header, n)) return "ZIP (DOCX / FB2)"
            val i = firstContentByte(header, n)
            return if (i >= 0 && header[i] == '<'.code.toByte()) "XML (FB2)" else "UNKNOWN"
        }

        /** Authoritative detection on a (decrypted) file. */
        fun sniff(file: File): DocumentFormat? {
            val head = ByteArray(8192)
            val n = file.inputStream().use { readUpTo(it, head) }
            if (n <= 0) return null
            if (indexOf(head, minOf(n, 1024), PDF_MAGIC) >= 0) return PDF
            if (isZip(head, n)) {
                return try {
                    ZipFile(file).use { z ->
                        when {
                            z.getEntry("word/document.xml") != null -> DOCX
                            z.entries().asSequence().any { !it.isDirectory && it.name.endsWith(".fb2", ignoreCase = true) } -> FB2_ZIP
                            else -> null
                        }
                    }
                } catch (_: IOException) { null }
            }
            val i = firstContentByte(head, n)
            if (i >= 0 && head[i] == '<'.code.toByte() &&
                String(head, 0, n, Charsets.ISO_8859_1).contains("<FictionBook")) return FB2
            return null
        }

        internal fun readUpTo(input: InputStream, buf: ByteArray): Int {
            var off = 0
            while (off < buf.size) {
                val r = input.read(buf, off, buf.size - off)
                if (r < 0) break
                off += r
            }
            return off
        }

        private fun isZip(b: ByteArray, n: Int) =
            n >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2] == 3.toByte() && b[3] == 4.toByte()

        /** Index of first byte after optional UTF-8 BOM and whitespace, or -1. */
        private fun firstContentByte(b: ByteArray, n: Int): Int {
            var i = 0
            if (n >= 3 && (b[0].toInt() and 0xFF) == 0xEF && (b[1].toInt() and 0xFF) == 0xBB && (b[2].toInt() and 0xFF) == 0xBF) i = 3
            while (i < n) {
                val c = b[i].toInt() and 0xFF
                if (c == 0x20 || c == 0x0A || c == 0x0D || c == 0x09) i++ else break
            }
            return if (i < n) i else -1
        }

        private fun indexOf(h: ByteArray, n: Int, p: ByteArray): Int {
            var i = 0
            while (i <= n - p.size) {
                var j = 0
                while (j < p.size && h[i + j] == p[j]) j++
                if (j == p.size) return i
                i++
            }
            return -1
        }
    }
}
