package com.fortress.reader.vault

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Level 1 – ordinary library synchronized with device files (ReadEra-style).
 *
 * Two complementary discovery sources:
 * 1. MediaStore query for indexed PDF / DOCX / FB2 (works best on Android ≤12).
 * 2. User-granted directory trees via Storage Access Framework (OpenDocumentTree).
 *    Permissions are persisted; trees are scanned recursively. Primary path on Android 13+.
 */
data class LibraryItem(
    val id: String,
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val modifiedAt: Long,
    val source: Source = Source.MEDIA_STORE,
) {
    enum class Source { MEDIA_STORE, SAF_TREE }
}

class LibraryRepository(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _items = MutableStateFlow<List<LibraryItem>>(emptyList())
    val items: Flow<List<LibraryItem>> = _items.asStateFlow()

    private val _treeUris = MutableStateFlow<List<Uri>>(loadPersistedTrees())
    val treeUris: Flow<List<Uri>> = _treeUris.asStateFlow()

    private val supportedExt = listOf(".pdf", ".docx", ".fb2", ".fb2.zip")

    suspend fun addTree(uri: Uri) = withContext(Dispatchers.IO) {
        // Persistable permission may throw SecurityException / IllegalArgumentException on
        // some OEMs; try several flag combinations and still keep the URI for this session.
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val candidates = intArrayOf(
            read or write,
            read,
        )
        for (flags in candidates) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
                break
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            } catch (_: Exception) {
            }
        }

        val root = DocumentFile.fromTreeUri(context, uri)
        if (root == null || !root.canRead()) {
            throw IllegalStateException(
                "Cannot read this folder. Pick a regular folder (e.g. Documents/Books), " +
                    "not Downloads. On the system screen tap “Use this folder”.",
            )
        }

        val current = loadPersistedTrees().toMutableList()
        if (current.none { it.toString() == uri.toString() }) {
            current.add(uri)
            savePersistedTrees(current)
            _treeUris.value = current
        }
        refresh()
    }

    suspend fun removeTree(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) { }
        val current = loadPersistedTrees().filter { it.toString() != uri.toString() }
        savePersistedTrees(current)
        _treeUris.value = current
        refresh()
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val seen = LinkedHashMap<String, LibraryItem>()
        scanMediaStore().forEach { seen[it.id] = it }
        for (tree in loadPersistedTrees()) {
            try {
                scanTree(tree).forEach { item -> seen[item.id] = item }
            } catch (_: SecurityException) {
                removeTreeInternal(tree)
            } catch (_: Exception) { }
        }
        _items.value = seen.values.sortedByDescending { it.modifiedAt }.toList()
    }

    private fun scanMediaStore(): List<LibraryItem> {
        val result = mutableListOf<LibraryItem>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
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
            append("${MediaStore.Files.FileColumns.MIME_TYPE} = ? OR ")
            append("${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? OR ")
            append("${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? OR ")
            append("${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? OR ")
            append("${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?")
            append(")")
        }
        val args = arrayOf(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/x-fictionbook+xml",
            "application/fb2",
            "%.pdf", "%.docx", "%.fb2", "%.fb2.zip",
        )
        try {
            context.contentResolver.query(
                collection, projection, selection, args,
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
                    if (!isSupportedName(name)) continue
                    val size = if (c.isNull(sizeCol)) 0L else c.getLong(sizeCol)
                    val mime = c.getString(mimeCol) ?: guessMime(name)
                    val mod = if (c.isNull(modCol)) 0L else c.getLong(modCol) * 1000
                    val uri = ContentUris.withAppendedId(collection, id)
                    result.add(
                        LibraryItem(
                            id = uri.toString(), uri = uri, name = name,
                            sizeBytes = size, mimeType = mime, modifiedAt = mod,
                            source = LibraryItem.Source.MEDIA_STORE,
                        ),
                    )
                }
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
        return result
    }

    private fun scanTree(treeUri: Uri): List<LibraryItem> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        if (!root.canRead()) return emptyList()
        val out = mutableListOf<LibraryItem>()
        walk(root, out, 0)
        return out
    }

    private fun walk(dir: DocumentFile, out: MutableList<LibraryItem>, depth: Int) {
        if (depth > MAX_TREE_DEPTH || out.size >= MAX_TREE_ITEMS) return
        val children = try { dir.listFiles() } catch (_: Exception) { return }
        for (child in children) {
            if (out.size >= MAX_TREE_ITEMS) return
            when {
                child.isDirectory -> walk(child, out, depth + 1)
                child.isFile -> {
                    val name = child.name ?: continue
                    if (!isSupportedName(name)) continue
                    out.add(
                        LibraryItem(
                            id = child.uri.toString(),
                            uri = child.uri,
                            name = name,
                            sizeBytes = child.length().coerceAtLeast(0L),
                            mimeType = child.type ?: guessMime(name),
                            modifiedAt = child.lastModified().coerceAtLeast(0L),
                            source = LibraryItem.Source.SAF_TREE,
                        ),
                    )
                }
            }
        }
    }

    private fun loadPersistedTrees(): List<Uri> {
        val raw = prefs.getStringSet(KEY_TREES, emptySet()) ?: emptySet()
        return raw.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
    }

    private fun savePersistedTrees(uris: List<Uri>) {
        prefs.edit().putStringSet(KEY_TREES, uris.map { it.toString() }.toSet()).apply()
    }

    private fun removeTreeInternal(uri: Uri) {
        val current = loadPersistedTrees().filter { it.toString() != uri.toString() }
        savePersistedTrees(current)
        _treeUris.value = current
    }

    private fun isSupportedName(name: String): Boolean {
        val lower = name.lowercase()
        return supportedExt.any { lower.endsWith(it) }
    }

    private fun guessMime(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".docx") ->
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            lower.endsWith(".fb2") || lower.endsWith(".fb2.zip") ->
                "application/x-fictionbook+xml"
            else -> "application/octet-stream"
        }
    }

    companion object {
        private const val PREFS_NAME = "library_trees"
        private const val KEY_TREES = "tree_uris"
        private const val MAX_TREE_DEPTH = 12
        private const val MAX_TREE_ITEMS = 5_000
    }
}
