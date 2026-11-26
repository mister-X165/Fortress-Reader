package com.fortress.reader.vault

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.fortress.reader.security.DocumentCipher
import com.fortress.reader.security.KeyWrapper
import com.fortress.reader.security.SecureTemp
import com.fortress.reader.security.UserFacingException
import com.fortress.reader.security.wipe
import java.io.BufferedInputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class VaultPhoto(val id: String, val name: String, val sizeBytes: Long, val addedAt: Long, val mimeType: String)

class PhotoRepository(
    private val context: Context,
    private val dao: PhotoDao,
    private val wrapper: KeyWrapper,
    private val temp: SecureTemp,
) {
    private val dir = File(context.noBackupFilesDir, "photos").apply { mkdirs() }

    fun photos(): Flow<List<VaultPhoto>> = dao.observeAll().map { list ->
        list.map { e ->
            val name = runCatching { String(wrapper.unwrap(e.nameBlob), Charsets.UTF_8) }
                .getOrDefault("Unreadable photo")
            VaultPhoto(e.id, name, e.sizeBytes, e.addedAt, e.mimeType)
        }
    }

    suspend fun purgeOrphans() = withContext(Dispatchers.IO) {
        val known = dao.ids().toSet()
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { f ->
            if (f.name.removeSuffix(".enc") !in known && now - f.lastModified() > 60_000) temp.wipe(f)
        }
    }

    /** Inspect a picked image before any copy happens. Nothing is written to storage. */
    suspend fun inspectPhoto(uri: Uri): ImportInfo = withContext(Dispatchers.IO) {
        val info = describe(uri)
        ImportInfo(info.name, info.size, readImageLabel(uri))
    }

    private fun readImageLabel(uri: Uri): String {
        return try {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                BufferedInputStream(raw, 8192).use { ins ->
                    ins.mark(32)
                    val header = ByteArray(32)
                    val n = readUpTo(ins, header)
                    imageFormatLabel(header, n)
                }
            } ?: "UNSUPPORTED"
        } catch (_: Exception) {
            "UNSUPPORTED"
        }
    }

    /** Encrypt and store a photo from a content URI. Content is verified by magic bytes, not by MIME. */
    suspend fun importPhoto(
        uri: Uri,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): VaultPhoto = withContext(Dispatchers.IO) {
        val info = describe(uri)
        if (info.size > MAX_BYTES) throw UserFacingException(TOO_LARGE)
        val id = UUID.randomUUID().toString()
        val target = File(dir, "$id.enc")
        val dek = DocumentCipher.newDek()
        var sniffed = "UNSUPPORTED"
        try {
            val raw = context.contentResolver.openInputStream(uri)
                ?: throw UserFacingException(CANNOT_READ)
            val size = BufferedInputStream(raw, 8192).use { ins ->
                ins.mark(32)
                val header = ByteArray(32)
                val n = readUpTo(ins, header)
                sniffed = imageFormatLabel(header, n)
                if (sniffed == "UNSUPPORTED") {
                    throw UserFacingException("Unsupported image format. Supported: JPEG, PNG, WEBP, HEIF, GIF.")
                }
                ins.reset()
                target.outputStream().buffered().use {
                    DocumentCipher.encrypt(ins, it, dek, MAX_BYTES) { done -> onProgress(done, info.size) }
                }
            }
            val mime = if (info.mime.startsWith("image/")) info.mime else "image/${sniffed.lowercase()}"
            val now = System.currentTimeMillis()
            dao.insert(
                PhotoEntity(
                    id = id,
                    nameBlob = wrapper.wrap(info.name.toByteArray()),
                    sizeBytes = size,
                    addedAt = now,
                    wrappedDek = wrapper.wrap(dek),
                    mimeType = mime,
                ),
            )
            VaultPhoto(id, info.name, size, now, mime)
        } catch (e: Exception) {
            temp.wipe(target)
            throw mapError(e, "Could not store this photo")
        } finally {
            dek.wipe()
        }
    }

    /** Decrypt a photo into a short-lived working file. Caller must wipe it. */
    suspend fun decryptToTemp(id: String): DecryptedFile = withContext(Dispatchers.IO) {
        val msg = "This photo cannot be decrypted. It may be damaged, or the device key was lost."
        val e = dao.get(id) ?: throw UserFacingException("Photo not found")
        val src = File(dir, "$id.enc")
        if (!src.exists()) throw UserFacingException("The encrypted file is missing")
        val key = try {
            wrapper.unwrap(e.wrappedDek)
        } catch (ex: Exception) {
            throw mapError(ex, msg)
        }
        var out: File? = null
        try {
            out = temp.create()
            src.inputStream().buffered().use { i ->
                out.outputStream().buffered().use { DocumentCipher.decrypt(i, it, key) }
            }
            val name = runCatching { String(wrapper.unwrap(e.nameBlob), Charsets.UTF_8) }
                .getOrDefault("Photo")
            DecryptedFile(out, name)
        } catch (ex: Exception) {
            out?.let(temp::wipe)
            throw mapError(ex, msg)
        } finally {
            key.wipe()
        }
    }

    /** Crypto-erase a single photo. */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dao.delete(id)
        temp.wipe(File(dir, "$id.enc"))
    }

    /** Irreversible destruction of every photo (used by duress wipe). */
    suspend fun wipeAll() = withContext(Dispatchers.IO) {
        dao.deleteAll()
        dir.listFiles()?.forEach { temp.wipe(it) }
    }

    private data class FileInfo(val name: String, val size: Long, val mime: String)

    private fun describe(uri: Uri): FileInfo {
        var name = "photo"
        var size = 0L
        var mime = context.contentResolver.getType(uri) ?: ""
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0) size = c.getLong(si)
            }
        }
        if (size <= 0) {
            // Fallback: stream length (best-effort)
            try {
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    var n = 0L
                    val buf = ByteArray(8192)
                    while (true) {
                        val r = ins.read(buf)
                        if (r < 0) break
                        n += r
                    }
                    size = n
                }
            } catch (_: Exception) { /* leave 0 */ }
        }
        return FileInfo(name, size, mime)
    }

    private fun mapError(e: Exception, default: String): Exception = when {
        e is UserFacingException -> e
        e is CancellationException -> e
        else -> UserFacingException(default, e)
    }

    private fun readUpTo(i: java.io.InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) { val r = i.read(buf, off, buf.size - off); if (r < 0) break; off += r }
        return off
    }

    companion object {
        const val MAX_BYTES = 50L * 1024 * 1024 // 50 MB for photos
        private const val TOO_LARGE = "File is too large (max 50 MB)"
        private const val CANNOT_READ = "Could not read the selected file"

        /** Content-based image detection: pickers report unreliable MIME types. */
        internal fun imageFormatLabel(b: ByteArray, n: Int): String = when {
            n >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "JPEG"
            n >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() &&
                b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "PNG"
            n >= 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
                b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
                b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() &&
                b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte() -> "WEBP"
            n >= 12 && b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() &&
                b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte() -> "HEIF"
            n >= 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() &&
                b[2] == 'F'.code.toByte() && b[3] == '8'.code.toByte() -> "GIF"
            else -> "UNSUPPORTED"
        }
    }
}