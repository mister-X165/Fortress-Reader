package com.fortress.reader.vault

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Level 1 – ordinary library synchronized with device files (ReadEra-style).
 *
 * Scans MediaStore for supported document MIME types / extensions.
 * Entries are only metadata + content URIs; files stay on the device filesystem.
 * Opening a library item uses the temporary-view path (decrypt is not involved).
 */
data class LibraryItem(
    val id: String,          // content URI string
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val modifiedAt: Long,
)

class LibraryRepository(private val context: Context) {
    private val _items = MutableStateFlow<List<LibraryItem>>(emptyList())
    val items: Flow<List<LibraryItem>> = _items.asStateFlow()

    /** Rescan MediaStore for PDF / DOCX / FB2 (and common ebook types). */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val result = mutableListOf<LibraryItem>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        // Documents collection is the right place for non-media files on modern Android.
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val selection = buildString {
            append("(")
            append("${MediaStore.Files.FileColumns.MIME_TYPE} = ? OR ")
            append("${MediaStore.Files.FileColumns.MIME_TYPE} = ? OR ")
            append("${MediaStore.Files.FileColumns.MIME_TYPE} = ? OR ")
            append("${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? OR ")
            append("${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? OR ")
            append("${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?")
            append(")")
        }
        // Exactly one bind argument per '?' placeholder — a mismatch throws SQLiteException
        // and the scan would silently return an empty library.
        val args = arrayOf(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/x-fictionbook+xml",
            "%.pdf",
            "%.docx",
            "%.fb2",
        )
        // Note: only formats the renderer actually supports (PDF / DOCX / FB2).
        // EPUB and legacy .doc are deliberately not listed: they would appear
        // in the library and then fail to open.
        try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                args,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val modCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val name = c.getString(nameCol) ?: continue
                    val size = if (c.isNull(sizeCol)) 0L else c.getLong(sizeCol)
                    val mime = c.getString(mimeCol) ?: "application/octet-stream"
                    val mod = if (c.isNull(modCol)) 0L else c.getLong(modCol) * 1000
                    val uri = ContentUris.withAppendedId(collection, id)
                    result.add(LibraryItem(uri.toString(), uri, name, size, mime, mod))
                }
            }
        } catch (_: SecurityException) {
            // Permission not granted yet — leave empty; UI will prompt.
        } catch (_: Exception) {
            // Best-effort scan; never crash the library.
        }
        _items.value = result
    }
}