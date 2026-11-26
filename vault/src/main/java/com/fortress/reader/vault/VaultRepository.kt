package com.fortress.reader.vault

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.fortress.reader.document.DocumentFormat
import com.fortress.reader.security.DocumentCipher
import com.fortress.reader.security.KeyWrapper
import com.fortress.reader.security.SecureTemp
import com.fortress.reader.security.UserFacingException
import com.fortress.reader.security.wipe
import java.io.BufferedInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class VaultDocument(val id: String, val name: String, val sizeBytes: Long, val addedAt: Long)
data class DecryptedFile(val file: File, val displayName: String)

/** Pre-import metadata shown on the confirmation screen. */
data class ImportInfo(val name: String, val sizeBytes: Long, val formatLabel: String)

class VaultRepository(
    private val context: Context,
    private val dao: DocumentDao,
    private val wrapper: KeyWrapper,
    private val temp: SecureTemp,
) {
    private val dir = File(context.noBackupFilesDir, "vault").apply { mkdirs() }

    fun documents(): Flow<List<VaultDocument>> = dao.observeAll().map { list ->
        list.map { e ->
            // One unreadable row (e.g. key lost) must never take the whole list down.
            val name = runCatching { String(wrapper.unwrap(e.nameBlob), Charsets.UTF_8) }.getOrDefault("Unreadable document")
            VaultDocument(e.id, name, e.sizeBytes, e.addedAt)
        }
    }

    /** Remove ciphertext files that have no database row (failed imports, schema reset). */
    suspend fun purgeOrphans() = withContext(Dispatchers.IO) {
        val known = dao.ids().toSet()
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { f ->
            if (f.name.removeSuffix(".enc") !in known && now - f.lastModified() > 60_000) temp.wipe(f)
        }
    }

    /**
     * Inspect a picked file before any copy happens: name, size and a best-effort
     * format label from the first KiB. Nothing is written to storage.
     */
    suspend fun inspectDocument(uri: Uri): ImportInfo = withContext(Dispatchers.IO) {
        val info = describe(uri)
        ImportInfo(info.name, info.size, readHeaderLabel(uri))
    }

    private fun readHeaderLabel(uri: Uri): String {
        return try {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                BufferedInputStream(raw, 8192).use { ins ->
                    ins.mark(HEADER)
                    val header = ByteArray(HEADER)
                    val n = readUpTo(ins, header)
                    if (n <= 0) return "UNSUPPORTED"
                    if (!DocumentFormat.looksSupported(header, n)) return "UNSUPPORTED"
                    return DocumentFormat.labelFor(header, n)
                }
            } ?: "UNSUPPORTED"
        } catch (_: Exception) {
            "UNSUPPORTED"
        }
    }

    /** Secure Vault: encrypt the picked file with a fresh per-document DEK. No plaintext copy is written. */
    suspend fun importToVault(
        uri: Uri,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): VaultDocument = withContext(Dispatchers.IO) {
        val info = describe(uri)
        if (info.size > MAX_BYTES) throw UserFacingException(TOO_LARGE)
        val id = UUID.randomUUID().toString()
        val target = File(dir, "$id.enc")
        val dek = DocumentCipher.newDek()
        try {
            val raw = context.contentResolver.openInputStream(uri) ?: throw UserFacingException(CANNOT_READ)
            val size = BufferedInputStream(raw, 8192).use { ins ->
                ins.mark(HEADER)
                val header = ByteArray(HEADER)
                val n = readUpTo(ins, header)
                ins.reset()
                if (!DocumentFormat.looksSupported(header, n)) {
                    throw UserFacingException("Unsupported file. Supported formats: ${DocumentFormat.SUPPORTED_LABEL}")
                }
                target.outputStream().buffered().use {
                    DocumentCipher.encrypt(ins, it, dek, MAX_BYTES) { done -> onProgress(done, info.size) }
                }
            }
            val now = System.currentTimeMillis()
            dao.insert(DocumentEntity(id, wrapper.wrap(info.name.toByteArray()), size, now, wrapper.wrap(dek)))
            VaultDocument(id, info.name, size, now)
        } catch (e: Exception) {
            temp.wipe(target); throw mapError(e, "Could not store this file")
        } finally { dek.wipe() }
    }

    /** Decrypt a vault document into a short-lived working file. Caller must [SecureTemp.wipe] it. */
    suspend fun decryptToTemp(id: String): DecryptedFile = withContext(Dispatchers.IO) {
        val msg = "This document cannot be decrypted. It may be damaged, or the device key was lost."
        val e = dao.get(id) ?: throw UserFacingException("Document not found")
        val src = File(dir, "$id.enc")
        if (!src.exists()) throw UserFacingException("The encrypted file is missing")
        val key = try { wrapper.unwrap(e.wrappedDek) } catch (ex: Exception) { throw mapError(ex, msg) }
        var out: File? = null
        try {
            out = temp.create()
            src.inputStream().buffered().use { i -> out.outputStream().buffered().use { DocumentCipher.decrypt(i, it, key) } }
            val name = runCatching { String(wrapper.unwrap(e.nameBlob), Charsets.UTF_8) }.getOrDefault("Document")
            DecryptedFile(out, name)
        } catch (ex: Exception) {
            out?.let(temp::wipe); throw mapError(ex, msg)
        } finally { key.wipe() }
    }

    /** Temporary View: copy to a short-lived working file only; nothing enters the vault. */
    suspend fun copyToTemp(
        uri: Uri,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): DecryptedFile = withContext(Dispatchers.IO) {
        val info = describe(uri)
        if (info.size > MAX_BYTES) throw UserFacingException(TOO_LARGE)
        var out: File? = null
        try {
            val ins = context.contentResolver.openInputStream(uri) ?: throw UserFacingException(CANNOT_READ)
            out = temp.create()
            ins.use { i ->
                out.outputStream().buffered().use { o ->
                    copyLimited(i, o, MAX_BYTES) { done -> onProgress(done, info.size) }
                }
            }
            DecryptedFile(out, info.name)
        } catch (ex: Exception) {
            out?.let(temp::wipe); throw mapError(ex, "Could not read this file")
        }
    }

    /** Deleting the row destroys the wrapped DEK: even a leftover ciphertext block is unrecoverable. */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dao.delete(id)
        temp.wipe(File(dir, "$id.enc"))
    }

    /** Irreversible destruction of every vault document (duress wipe). */
    suspend fun wipeAll() = withContext(Dispatchers.IO) {
        dao.deleteAll()
        dir.listFiles()?.forEach { temp.wipe(it) }
    }

    private class Info(val name: String, val size: Long)

    private fun describe(uri: Uri): Info {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    val name = (if (n >= 0) c.getString(n) else null)?.takeIf { it.isNotBlank() } ?: "document"
                    val size = if (s >= 0 && !c.isNull(s)) c.getLong(s) else -1L
                    return Info(name.take(255), size)
                }
            }
        } catch (_: RuntimeException) { /* provider without OpenableColumns: fall through */ }
        return Info("document", -1L)
    }

    private fun mapError(e: Exception, fallback: String): Exception = when {
        e is CancellationException || e is UserFacingException -> e
        e.message == "File too large" -> UserFacingException(TOO_LARGE, e)
        e is SecurityException || e is FileNotFoundException -> UserFacingException(CANNOT_READ, e)
        else -> UserFacingException(fallback, e)
    }

    private fun readUpTo(i: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) { val r = i.read(buf, off, buf.size - off); if (r < 0) break; off += r }
        return off
    }

    private fun copyLimited(i: InputStream, o: OutputStream, max: Long, onProgress: (Long) -> Unit = {}) {
        val buf = ByteArray(64 * 1024); var total = 0L
        while (true) {
            val n = i.read(buf); if (n < 0) break
            total += n; if (total > max) throw IOException("File too large")
            o.write(buf, 0, n)
            onProgress(total)
        }
    }

    companion object {
        const val MAX_BYTES = 100L * 1024 * 1024
        private const val HEADER = 1024
        private const val TOO_LARGE = "The file is larger than 100 MB"
        private const val CANNOT_READ = "Cannot read this file. Try picking it again."
    }
}