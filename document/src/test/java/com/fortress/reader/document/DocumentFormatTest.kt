package com.fortress.reader.document

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentFormatTest {
    private fun tmp(bytes: ByteArray) = File.createTempFile("fmt", ".bin").apply { deleteOnExit(); writeBytes(bytes) }
    private fun zip(vararg names: String) = File.createTempFile("fmt", ".zip").apply {
        deleteOnExit()
        ZipOutputStream(outputStream()).use { z -> names.forEach { z.putNextEntry(ZipEntry(it)); z.write(1); z.closeEntry() } }
    }

    @Test fun detectsPdf() = assertEquals(DocumentFormat.PDF, DocumentFormat.sniff(tmp("%PDF-1.7\n...".toByteArray())))
    @Test fun detectsPdfWithLeadingJunk() = assertEquals(DocumentFormat.PDF, DocumentFormat.sniff(tmp("\n\n  junk%PDF-1.4".toByteArray())))
    @Test fun detectsDocx() = assertEquals(DocumentFormat.DOCX, DocumentFormat.sniff(zip("[Content_Types].xml", "word/document.xml")))
    @Test fun detectsFb2Zip() = assertEquals(DocumentFormat.FB2_ZIP, DocumentFormat.sniff(zip("book.FB2")))
    @Test fun detectsFb2() = assertEquals(DocumentFormat.FB2,
        DocumentFormat.sniff(tmp("\uFEFF<?xml version=\"1.0\"?>\n<FictionBook xmlns=\"x\"></FictionBook>".toByteArray())))
    @Test fun rejectsRandomZipAndXmlAndEmpty() {
        assertNull(DocumentFormat.sniff(zip("a.txt")))
        assertNull(DocumentFormat.sniff(tmp("<html></html>".toByteArray())))
        assertNull(DocumentFormat.sniff(tmp(ByteArray(0))))
    }
    @Test fun headerCheck() {
        assertTrue(DocumentFormat.looksSupported("%PDF-1.4".toByteArray()))
        assertTrue(DocumentFormat.looksSupported(byteArrayOf(0x50, 0x4B, 3, 4, 0)))
        assertTrue(DocumentFormat.looksSupported("  <?xml".toByteArray()))
        assertFalse(DocumentFormat.looksSupported("hello".toByteArray()))
        assertFalse(DocumentFormat.looksSupported(ByteArray(0)))
    }
}
