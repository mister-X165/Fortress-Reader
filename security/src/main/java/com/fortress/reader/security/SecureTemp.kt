package com.fortress.reader.security

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Short-lived plaintext working files (PdfRenderer needs a seekable fd).
 * Stored in noBackupFilesDir (app-private, excluded from backup), zero-overwritten then deleted.
 * Call [wipeAll] at process start to clear leftovers from a crash.
 */
class SecureTemp(context: Context) {
    private val dir = File(context.noBackupFilesDir, "tmp")

    /** Prefix must be >= 3 chars (File.createTempFile contract). */
    fun create(): File {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create temp directory")
        return File.createTempFile("fr_", ".tmp", dir)
    }

    fun wipe(file: File) {
        try {
            if (file.exists()) RandomAccessFile(file, "rw").use { raf ->
                val len = raf.length()
                val zeros = ByteArray(8192)
                var written = 0L
                raf.seek(0)
                while (written < len) {
                    val n = minOf(zeros.size.toLong(), len - written).toInt()
                    raf.write(zeros, 0, n); written += n
                }
                raf.fd.sync()
            }
        } catch (_: IOException) { /* best effort */ }
        file.delete()
    }

    fun wipeAll() { dir.listFiles()?.forEach { wipe(it) } }
}
