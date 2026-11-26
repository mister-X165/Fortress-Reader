package com.fortress.reader.vault

import com.fortress.reader.security.DocumentCipher
import com.fortress.reader.security.KeyWrapper
import com.fortress.reader.security.UserFacingException
import com.fortress.reader.security.wipe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class NoteSummary(val id: String, val title: String, val updatedAt: Long)
data class Note(val id: String, val title: String, val body: String)

/** Secured notes: title and body are encrypted at rest; deleting a note destroys its key. */
class NoteRepository(private val dao: NoteDao, private val wrapper: KeyWrapper) {

    fun notes(): Flow<List<NoteSummary>> = dao.observeAll().map { list ->
        list.map { e ->
            val title = runCatching { String(wrapper.unwrap(e.titleBlob), Charsets.UTF_8) }.getOrDefault("Unreadable note")
            NoteSummary(e.id, title.ifBlank { "Untitled" }, e.updatedAt)
        }
    }

    suspend fun get(id: String): Note = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: throw UserFacingException("Note not found")
        var dek: ByteArray? = null
        try {
            dek = wrapper.unwrap(e.wrappedDek)
            val out = ByteArrayOutputStream()
            DocumentCipher.decrypt(ByteArrayInputStream(e.bodyBlob), out, dek)
            val bytes = out.toByteArray()
            val body = String(bytes, Charsets.UTF_8)
            bytes.wipe()
            Note(id, String(wrapper.unwrap(e.titleBlob), Charsets.UTF_8), body)
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            throw UserFacingException("This note cannot be decrypted", ex)
        } finally { dek?.wipe() }
    }

    /** Creates (id == null) or updates a note; every save uses a fresh key. @return the note id. */
    suspend fun save(id: String?, title: String, body: String): String = withContext(Dispatchers.IO) {
        if (body.length > MAX_CHARS) throw UserFacingException("Note is too long (limit $MAX_CHARS characters)")
        val dek = DocumentCipher.newDek()
        val plain = body.toByteArray(Charsets.UTF_8)
        try {
            val sealed = ByteArrayOutputStream()
            DocumentCipher.encrypt(ByteArrayInputStream(plain), sealed, dek)
            val noteId = id ?: UUID.randomUUID().toString()
            dao.upsert(NoteEntity(noteId, wrapper.wrap(title.trim().toByteArray()), sealed.toByteArray(), wrapper.wrap(dek), System.currentTimeMillis()))
            noteId
        } catch (ex: Exception) {
            if (ex is CancellationException || ex is UserFacingException) throw ex
            throw UserFacingException("Could not save the note", ex)
        } finally { dek.wipe(); plain.wipe() }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { dao.delete(id) }

    /** Irreversible destruction of every note (duress wipe). */
    suspend fun wipeAll() = withContext(Dispatchers.IO) { dao.deleteAll() }

    companion object { const val MAX_CHARS = 200_000 }
}