package com.fortress.reader.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fortress.reader.AppViewModel
import com.fortress.reader.ImportMode
import com.fortress.reader.ImportTarget
import com.fortress.reader.NoteDraft
import com.fortress.reader.PendingImport
import com.fortress.reader.Screen
import com.fortress.reader.SecurityCheck
import com.fortress.reader.document.OpenedDocument
import com.fortress.reader.document.TextStyle
import com.fortress.reader.ui.theme.FortressTheme
import com.fortress.reader.ui.theme.FRSpacing
import com.fortress.reader.ui.theme.LocalExtendedColors
import com.fortress.reader.vault.LibraryItem
import com.fortress.reader.vault.NoteSummary
import com.fortress.reader.vault.SafeAuthRepository
import com.fortress.reader.vault.VaultDocument
import com.fortress.reader.vault.VaultPhoto
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun navTabFor(screen: Screen): NavTab? = when (screen) {
    Screen.Library -> NavTab.LIBRARY
    Screen.SafeLocked, Screen.SafeSetup, Screen.SafeHome, Screen.SafeSettings -> NavTab.VAULT
    Screen.SecurityDashboard -> NavTab.SECURITY
    else -> null
}

@Composable
fun FortressRoot(
    vm: AppViewModel,
    onUnlock: () -> Unit,
    onImportDoc: () -> Unit,
    onImportPhoto: () -> Unit,
    onOpenTemporary: () -> Unit,
) {
    val locked by vm.locked.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val libraryItems by vm.libraryItems.collectAsStateWithLifecycle()
    val docs by vm.documents.collectAsStateWithLifecycle()
    val notes by vm.noteList.collectAsStateWithLifecycle()
    val photoList by vm.photoList.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val pinError by vm.pinError.collectAsStateWithLifecycle()
    val safeSetupDone by vm.safeSetupDone.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val checks by vm.checks.collectAsStateWithLifecycle()
    val screenProtected by vm.screenProtected.collectAsStateWithLifecycle()
    val usageBytes by vm.vaultUsageBytes.collectAsStateWithLifecycle()

    FortressTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val current = screen
            val navTab = navTabFor(current)
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    if (locked) {
                        LockScreen(onUnlock)
                    } else {
                        when (current) {
                            Screen.Library -> LibraryScreen(
                                items = libraryItems,
                                screenProtected = screenProtected,
                                onRefresh = vm::refreshLibrary,
                                onOpenItem = vm::openLibraryItem,
                                onOpenTemporary = onOpenTemporary,
                            )
                            is Screen.ImportConfirm -> ImportScreen(
                                pending = current.pending,
                                onConfirm = vm::confirmImport,
                                onCancel = vm::cancelImport,
                            )
                            Screen.SafeLocked -> VaultPinScreen(
                                pinError = pinError,
                                onDismissError = vm::dismissPinError,
                                onUnlock = vm::unlockSafe,
                                onBack = vm::leaveSafe,
                            )
                            Screen.SafeSetup -> VaultSetupScreen(
                                pinError = pinError,
                                onDismissError = vm::dismissPinError,
                                onSetup = vm::setupSafe,
                                onCancel = vm::leaveSafe,
                            )
                            Screen.SafeHome -> VaultScreen(
                                docs = docs,
                                photos = photoList,
                                notes = notes,
                                usageBytes = usageBytes,
                                safeSetupDone = safeSetupDone,
                                onImportDoc = onImportDoc,
                                onImportPhoto = onImportPhoto,
                                onOpenDoc = vm::openFromVault,
                                onDeleteDoc = vm::deleteDocument,
                                onOpenPhoto = vm::openPhoto,
                                onDeletePhoto = vm::deletePhoto,
                                onNewNote = vm::newNote,
                                onOpenNote = vm::openNote,
                                onDeleteNote = vm::deleteNote,
                                onSettings = vm::openSafeSettings,
                                onLock = vm::leaveSafe,
                            )
                            Screen.SafeSettings -> ChangePinsScreen(
                                pinError = pinError,
                                onDismissError = vm::dismissPinError,
                                onChange = vm::changeSafePins,
                                onBack = vm::closeSafeSettings,
                            )
                            Screen.SecurityDashboard -> DashboardScreen(
                                checks = checks,
                                usageBytes = usageBytes,
                                docCount = docs.size,
                                photoCount = photoList.size,
                                noteCount = notes.size,
                                onRefresh = vm::refreshChecks,
                            )
                            Screen.Loading -> {
                                val b = busy
                                Box(Modifier.fillMaxSize(), Alignment.Center) {
                                    if (b != null) BusyView(b, Modifier.fillMaxWidth()) else CircularProgressIndicator()
                                }
                            }
                            is Screen.Viewer -> TempViewScreen(current, vm::closeDocument)
                            is Screen.PhotoViewer -> TempPhotoScreen(current.title, current.file, vm::closeDocument)
                            Screen.NoteEditor -> draft?.let {
                                NoteEditorScreen(it, vm::editDraft, vm::closeNote, vm::deleteCurrentNote)
                            }
                            is Screen.Failure -> Box(
                                Modifier.fillMaxSize().systemBarsPadding(),
                                Alignment.Center,
                            ) {
                                ErrorState(current.message, vm::closeDocument, Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                if (!locked && navTab != null) {
                    FRNavBar(
                        selected = navTab,
                        onSelect = { tab ->
                            when (tab) {
                                NavTab.LIBRARY -> vm.goLibrary()
                                NavTab.VAULT -> vm.goVault()
                                NavTab.SECURITY -> vm.goSecurity()
                            }
                        },
                    )
                }
            }
            message?.let {
                AlertDialog(
                    onDismissRequest = vm::dismissMessage,
                    shape = MaterialTheme.shapes.small,
                    containerColor = MaterialTheme.colorScheme.surface,
                    text = { Text(it, style = MaterialTheme.typography.bodyMedium) },
                    confirmButton = { TextButton(onClick = vm::dismissMessage) { Text("OK") } },
                )
            }
        }
    }
}

// ------------------------------------------------------------------
// Shared chrome
// ------------------------------------------------------------------

@Composable
private fun BrandHeader(screenProtected: Boolean? = null) {
    Column(Modifier.fillMaxWidth().systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = FRSpacing.md, vertical = FRSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "FORTRESS READER",
                Modifier.weight(1f),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (screenProtected != null) {
                Text(
                    if (screenProtected) "● SCREEN SHIELD ON" else "○ SCREEN SHIELD OFF",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (screenProtected) {
                        LocalExtendedColors.current.success
                    } else {
                        LocalExtendedColors.current.warning
                    },
                )
            }
        }
        FRDivider()
    }
}

// ------------------------------------------------------------------
// App lock (biometric gate)
// ------------------------------------------------------------------

@Composable
private fun LockScreen(onUnlock: () -> Unit) =
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(FRSpacing.lg),
        Arrangement.Center,
        Alignment.CenterHorizontally,
    ) {
        Text(
            "FORTRESS READER",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(FRSpacing.sm))
        Box(
            Modifier
                .width(48.dp)
                .height(3.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.height(FRSpacing.md))
        Text(
            "PROTECTED STORAGE · TWO SECURITY LEVELS",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(FRSpacing.xl))
        FRPrimaryButton("UNLOCK", onUnlock, Modifier.fillMaxWidth())
        Spacer(Modifier.height(FRSpacing.md))
        Text(
            "Biometric or device credential is required. " +
                "The app locks whenever it leaves the foreground.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }

// ------------------------------------------------------------------
// A. Document Library (Level 1)
// ------------------------------------------------------------------

@Composable
private fun LibraryScreen(
    items: List<LibraryItem>,
    screenProtected: Boolean,
    onRefresh: () -> Unit,
    onOpenItem: (LibraryItem) -> Unit,
    onOpenTemporary: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        BrandHeader(screenProtected)
        Column(Modifier.padding(horizontal = FRSpacing.md, vertical = FRSpacing.md)) {
            Text("DOCUMENT LIBRARY", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(FRSpacing.xxs))
            Text(
                "${items.size} ITEM${if (items.size == 1) "" else "S"} · LEVEL 1 · DEVICE FILES",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = FRSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(FRSpacing.xs),
        ) {
            FRSecondaryButton("OPEN FILE…", onOpenTemporary, Modifier.weight(1f))
            FRSecondaryButton("REFRESH", onRefresh, Modifier.weight(1f))
        }
        Spacer(Modifier.height(FRSpacing.sm))
        if (items.isEmpty()) {
            EmptyState(
                title = "NO DOCUMENTS FOUND",
                hint = "Grant storage access and place PDF / DOCX / FB2 files on the device, " +
                    "or use “Open file…” to pick one. Opening a device file creates a temporary " +
                    "working copy that is wiped on close; nothing is copied into the vault.",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = FRSpacing.md)) {
                items(items, key = { it.id }) { item ->
                    DocumentRow(
                        title = item.name,
                        meta = formatSize(item.sizeBytes) + " · " + formatDate(item.modifiedAt),
                        format = libraryFormat(item),
                        onClick = { onOpenItem(item) },
                        statusTag = "DEVICE FILE",
                    )
                }
                item { Spacer(Modifier.height(FRSpacing.lg)) }
            }
        }
    }
}

// ------------------------------------------------------------------
// E. Import Document (confirmation before any operation)
// ------------------------------------------------------------------

@Composable
private fun ImportScreen(
    pending: PendingImport,
    onConfirm: (ImportMode) -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    val isPhoto = pending.target == ImportTarget.VAULT_PHOTO
    var mode by remember { mutableStateOf(if (isPhoto) ImportMode.TO_VAULT else pending.preselected) }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        BrandHeader()
        Column(Modifier.fillMaxSize().padding(FRSpacing.md)) {
            Text("IMPORT DOCUMENT", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(FRSpacing.xxs))
            Text(
                "VERIFY THE FILE AND SELECT THE STORAGE MODE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(FRSpacing.md))
            TechnicalPanel(
                title = "SELECTED FILE",
                rows = listOf(
                    "NAME" to pending.name,
                    "FORMAT" to pending.formatLabel,
                    "SIZE" to formatSize(pending.sizeBytes),
                    "DESTINATION" to if (isPhoto) "SECURE VAULT" else "SELECTED BELOW",
                ),
            )
            Spacer(Modifier.height(FRSpacing.md))
            if (isPhoto) {
                BannerBox(
                    title = "PHOTO VAULT",
                    lines = listOf(
                        "Photos are stored only in the encrypted vault.",
                        "Temporary viewing of picked photos is not available.",
                    ),
                    accent = LocalExtendedColors.current.warning,
                )
            } else {
                ModeCard(
                    selected = mode == ImportMode.TO_VAULT,
                    title = "IMPORT TO VAULT",
                    description = "Encrypt with a per-item AES-256-GCM key and store permanently " +
                        "in the secure vault. The file is streamed — no plaintext copy is written.",
                    onClick = { mode = ImportMode.TO_VAULT },
                )
                Spacer(Modifier.height(FRSpacing.xs))
                ModeCard(
                    selected = mode == ImportMode.TEMPORARY,
                    title = "TEMPORARY VIEW",
                    description = "Open without adding to the vault. A short-lived working copy " +
                        "is kept app-private and zero-wiped on close.",
                    onClick = { mode = ImportMode.TEMPORARY },
                )
            }
            Spacer(Modifier.height(FRSpacing.md))
            FRPrimaryButton(
                text = "CONFIRM " + (if (mode == ImportMode.TO_VAULT) "IMPORT TO VAULT" else "TEMPORARY VIEW"),
                onClick = { onConfirm(mode) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(FRSpacing.xs))
            FRSecondaryButton("CANCEL", onCancel, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ModeCard(selected: Boolean, title: String, description: String, onClick: () -> Unit) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else LocalExtendedColors.current.divider
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor)
            .clickable(onClick = onClick)
            .padding(FRSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(14.dp)
                .border(2.dp, borderColor)
                .background(if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent),
        )
        Spacer(Modifier.width(FRSpacing.sm))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------------
// B. Secure Vault (Level 2)
// ------------------------------------------------------------------

@Composable
private fun VaultScreen(
    docs: List<VaultDocument>,
    photos: List<VaultPhoto>,
    notes: List<NoteSummary>,
    usageBytes: Long,
    safeSetupDone: Boolean,
    onImportDoc: () -> Unit,
    onImportPhoto: () -> Unit,
    onOpenDoc: (String) -> Unit,
    onDeleteDoc: (String) -> Unit,
    onOpenPhoto: (String) -> Unit,
    onDeletePhoto: (String) -> Unit,
    onNewNote: () -> Unit,
    onOpenNote: (String) -> Unit,
    onDeleteNote: (String) -> Unit,
    onSettings: () -> Unit,
    onLock: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var docToDelete by remember { mutableStateOf<VaultDocument?>(null) }
    var photoToDelete by remember { mutableStateOf<VaultPhoto?>(null) }
    var noteToDelete by remember { mutableStateOf<NoteSummary?>(null) }
    BackHandler(onBack = onLock)
    Column(Modifier.fillMaxSize()) {
        BrandHeader()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = FRSpacing.md, vertical = FRSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("SECURE VAULT", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(FRSpacing.xxs))
                Text(
                    "LEVEL 2 · ENCRYPTED AT REST · SESSION UNLOCKED",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onSettings) {
                Text("PINS", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onLock) {
                Text("LOCK", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        Column(Modifier.padding(horizontal = FRSpacing.md)) {
            TechnicalPanel(
                title = "VAULT STATE",
                rows = listOf(
                    "STORED ITEMS" to "${docs.size} DOC · ${photos.size} PHOTOS · ${notes.size} NOTES",
                    "PAYLOAD TRACKED" to formatSize(usageBytes),
                    "ENCRYPTION" to "AES-256-GCM · PER-ITEM KEYS",
                    "KEY STORAGE" to "ANDROID KEYSTORE · NON-EXPORTABLE",
                    "DURESS WIPE" to if (safeSetupDone) "ARMED" else "NOT SET UP",
                ),
            )
        }
        Spacer(Modifier.height(FRSpacing.xs))
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }) {
                Text("DOCUMENTS (${docs.size})", Modifier.padding(FRSpacing.sm), style = MaterialTheme.typography.labelSmall)
            }
            Tab(selected = tab == 1, onClick = { tab = 1 }) {
                Text("PHOTOS (${photos.size})", Modifier.padding(FRSpacing.sm), style = MaterialTheme.typography.labelSmall)
            }
            Tab(selected = tab == 2, onClick = { tab = 2 }) {
                Text("NOTES (${notes.size})", Modifier.padding(FRSpacing.sm), style = MaterialTheme.typography.labelSmall)
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> Column(Modifier.fillMaxSize().padding(horizontal = FRSpacing.md, vertical = FRSpacing.sm)) {
                    FRPrimaryButton("IMPORT DOCUMENT", onImportDoc, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(FRSpacing.sm))
                    if (docs.isEmpty()) {
                        EmptyState("VAULT IS EMPTY", "Import a PDF, DOCX or FB2 document. " +
                            "It is encrypted on arrival; names are stored encrypted as well.")
                    } else {
                        LazyColumn {
                            items(docs, key = { it.id }) { d ->
                                DocumentRow(
                                    title = d.name,
                                    meta = formatSize(d.sizeBytes) + " · " + formatDate(d.addedAt),
                                    format = nameFormat(d.name),
                                    onClick = { onOpenDoc(d.id) },
                                    trailing = {
                                        RowActionButton("DELETE", MaterialTheme.colorScheme.error) { docToDelete = d }
                                    },
                                )
                            }
                            item { Spacer(Modifier.height(FRSpacing.lg)) }
                        }
                    }
                }
                1 -> Column(Modifier.fillMaxSize().padding(horizontal = FRSpacing.md, vertical = FRSpacing.sm)) {
                    FRPrimaryButton("IMPORT PHOTO", onImportPhoto, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(FRSpacing.sm))
                    if (photos.isEmpty()) {
                        EmptyState("NO PHOTOS", "Photos are encrypted with a per-item key " +
                            "and only decrypted while being viewed.")
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(150.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = FRSpacing.lg),
                        ) {
                            items(photos, key = { it.id }) { p ->
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.surface,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, LocalExtendedColors.current.divider),
                                    modifier = Modifier
                                        .padding(4.dp)
                                        .clickable { onOpenPhoto(p.id) },
                                ) {
                                    Column(Modifier.padding(FRSpacing.sm)) {
                                        Text(
                                            p.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            formatSize(p.sizeBytes) + " · " + formatDate(p.addedAt),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        RowActionButton("DELETE", MaterialTheme.colorScheme.error) { photoToDelete = p }
                                    }
                                }
                            }
                        }
                    }
                }
                2 -> Column(Modifier.fillMaxSize().padding(horizontal = FRSpacing.md, vertical = FRSpacing.sm)) {
                    FRPrimaryButton("NEW NOTE", onNewNote, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(FRSpacing.sm))
                    if (notes.isEmpty()) {
                        EmptyState("NO NOTES", "Note titles and bodies are encrypted at rest. " +
                            "Every save is re-sealed with a fresh key.")
                    } else {
                        LazyColumn {
                            items(notes, key = { it.id }) { n ->
                                DocumentRow(
                                    title = n.title.ifBlank { "UNTITLED" },
                                    meta = formatDate(n.updatedAt),
                                    format = "TXT",
                                    onClick = { onOpenNote(n.id) },
                                    trailing = {
                                        RowActionButton("DELETE", MaterialTheme.colorScheme.error) { noteToDelete = n }
                                    },
                                )
                            }
                            item { Spacer(Modifier.height(FRSpacing.lg)) }
                        }
                    }
                }
            }
        }
    }
    docToDelete?.let { d ->
        ConfirmDialog(
            title = "Delete document?",
            body = "The encrypted file and its key will be permanently destroyed.",
            confirmText = "DELETE",
            destructive = true,
            onConfirm = { docToDelete = null; onDeleteDoc(d.id) },
            onDismiss = { docToDelete = null },
        )
    }
    photoToDelete?.let { p ->
        ConfirmDialog(
            title = "Delete photo?",
            body = "The encrypted photo and its key will be permanently destroyed.",
            confirmText = "DELETE",
            destructive = true,
            onConfirm = { photoToDelete = null; onDeletePhoto(p.id) },
            onDismiss = { photoToDelete = null },
        )
    }
    noteToDelete?.let { n ->
        ConfirmDialog(
            title = "Delete note?",
            body = "The note and its key will be permanently destroyed.",
            confirmText = "DELETE",
            destructive = true,
            onConfirm = { noteToDelete = null; onDeleteNote(n.id) },
            onDismiss = { noteToDelete = null },
        )
    }
}

// ------------------------------------------------------------------
// C. Temporary View
// ------------------------------------------------------------------

@Composable
private fun TempViewScreen(s: Screen.Viewer, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val isPdf = s.document is OpenedDocument.Pages
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        BannerBox(
            title = "TEMPORARY SESSION",
            lines = listOf(
                "Screen capture is blocked.",
                "A short-lived working copy is kept in app-private storage and zero-wiped on close.",
                if (isPdf) {
                    "PDF rendering requires this working file; nothing is stored permanently."
                } else {
                    "Nothing is written to shared storage at any point."
                },
            ),
            accent = MaterialTheme.colorScheme.primary,
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = FRSpacing.md, vertical = FRSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FRSecondaryButton("CLOSE", onClose)
            Spacer(Modifier.width(FRSpacing.sm))
            FormatBadge(if (isPdf) "PDF" else "TEXT")
            Spacer(Modifier.width(FRSpacing.sm))
            Text(
                s.title,
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when (val d = s.document) {
            is OpenedDocument.Pages -> PagesView(d)
            is OpenedDocument.Text -> TextView(d)
        }
    }
}

@Composable
private fun TempPhotoScreen(title: String, file: File, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        BannerBox(
            title = "TEMPORARY SESSION",
            lines = listOf(
                "Screen capture is blocked.",
                "The photo is decrypted only while this viewer is open.",
                "The working copy is zero-wiped on close; nothing is stored permanently.",
            ),
            accent = MaterialTheme.colorScheme.primary,
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = FRSpacing.md, vertical = FRSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FRSecondaryButton("CLOSE", onClose)
            Spacer(Modifier.width(FRSpacing.sm))
            FormatBadge("IMAGE")
            Spacer(Modifier.width(FRSpacing.sm))
            Text(
                title,
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val decoded by produceState(DecodedPhoto(null, false), file) {
            value = withContext(Dispatchers.Default) {
                val bmp = runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= 2048 || bounds.outHeight / (sample * 2) >= 2048) sample *= 2
                    BitmapFactory.decodeFile(
                        file.absolutePath,
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                }.getOrNull()
                DecodedPhoto(bmp, bmp == null)
            }
        }
        val bitmap = decoded.bitmap
        when {
            bitmap != null -> Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = title,
                modifier = Modifier.fillMaxSize().padding(FRSpacing.md),
                contentScale = ContentScale.Fit,
            )
            decoded.failed -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    "COULD NOT DISPLAY IMAGE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    "DECRYPTING…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class DecodedPhoto(val bitmap: Bitmap?, val failed: Boolean)

// ------------------------------------------------------------------
// D. Security Dashboard (verified states only)
// ------------------------------------------------------------------

@Composable
private fun DashboardScreen(
    checks: List<SecurityCheck>?,
    usageBytes: Long,
    docCount: Int,
    photoCount: Int,
    noteCount: Int,
    onRefresh: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        BrandHeader()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = FRSpacing.md, vertical = FRSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("SECURITY DASHBOARD", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(FRSpacing.xxs))
                Text(
                    "STATES COMPUTED FROM LIVE DEVICE CHECKS",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRefresh) {
                Text("REFRESH", style = MaterialTheme.typography.labelSmall)
            }
        }
        val current = checks
        if (current == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = FRSpacing.md, vertical = FRSpacing.sm),
            ) {
                item {
                    TechnicalPanel(
                        title = "VAULT CONTENTS",
                        rows = listOf(
                            "DOCUMENTS" to "$docCount",
                            "PHOTOS" to "$photoCount",
                            "NOTES" to "$noteCount",
                            "PAYLOAD TRACKED" to formatSize(usageBytes),
                        ),
                    )
                    Spacer(Modifier.height(FRSpacing.sm))
                }
                items(current.size) { i -> SecurityRow(current[i]) }
                item {
                    Text(
                        "OFFLINE ONLY · NO TELEMETRY · NO CLOUD PROCESSING",
                        Modifier.fillMaxWidth().padding(vertical = FRSpacing.lg),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// Safe PIN screens
// ------------------------------------------------------------------

@Composable
private fun pinFieldOptions() = KeyboardOptions(keyboardType = KeyboardType.Password)

@Composable
private fun VaultPinScreen(
    pinError: String?,
    onDismissError: () -> Unit,
    onUnlock: (CharArray) -> Unit,
    onBack: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(FRSpacing.lg),
        Arrangement.Center,
        Alignment.CenterHorizontally,
    ) {
        Text("SECURE VAULT", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(FRSpacing.xs))
        Text(
            "ENTER PIN · MINIMUM ${SafeAuthRepository.MIN_PIN} CHARACTERS",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(FRSpacing.md))
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it; onDismissError() },
            label = { Text("PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = pinFieldOptions(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = frTextFieldColors(),
        )
        if (pinError != null) {
            Spacer(Modifier.height(FRSpacing.xs))
            Text(
                pinError,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(FRSpacing.md))
        FRPrimaryButton(
            "UNLOCK",
            { onUnlock(pin.toCharArray()); pin = "" },
            Modifier.fillMaxWidth(),
            enabled = pin.length >= SafeAuthRepository.MIN_PIN,
        )
        Spacer(Modifier.height(FRSpacing.xs))
        FRSecondaryButton("BACK TO LIBRARY", onBack, Modifier.fillMaxWidth())
    }
}

@Composable
private fun VaultSetupScreen(
    pinError: String?,
    onDismissError: () -> Unit,
    onSetup: (CharArray, CharArray) -> Unit,
    onCancel: () -> Unit,
) {
    var real by remember { mutableStateOf("") }
    var real2 by remember { mutableStateOf("") }
    var duress by remember { mutableStateOf("") }
    var duress2 by remember { mutableStateOf("") }
    BackHandler(onBack = onCancel)
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(FRSpacing.md),
        verticalArrangement = Arrangement.spacedBy(FRSpacing.sm),
    ) {
        Text("SET UP SECURE VAULT", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Choose a PIN for normal access and a different “duress” PIN for coercion scenarios.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BannerBox(
            title = "DURESS PIN BEHAVIOUR",
            lines = listOf(
                "Entering the duress PIN destroys every vault item permanently.",
                "The vault then appears empty — indistinguishable from a real unlock.",
                "There is no recovery for a forgotten PIN.",
            ),
            accent = LocalExtendedColors.current.warning,
        )
        OutlinedTextField(
            value = real,
            onValueChange = { real = it; onDismissError() },
            label = { Text("Vault PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = pinFieldOptions(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = frTextFieldColors(),
        )
        OutlinedTextField(
            value = real2,
            onValueChange = { real2 = it },
            label = { Text("Confirm Vault PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = pinFieldOptions(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = frTextFieldColors(),
        )
        OutlinedTextField(
            value = duress,
            onValueChange = { duress = it },
            label = { Text("Duress PIN (destroys data)") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = pinFieldOptions(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = frTextFieldColors(),
        )
        OutlinedTextField(
            value = duress2,
            onValueChange = { duress2 = it },
            label = { Text("Confirm Duress PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = pinFieldOptions(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = frTextFieldColors(),
        )
        if (pinError != null) {
            Text(pinError, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        val canSubmit = real.length >= SafeAuthRepository.MIN_PIN && real == real2 &&
            duress.length >= SafeAuthRepository.MIN_PIN && duress == duress2 && real != duress
        FRPrimaryButton(
            "CREATE VAULT",
            { onDismissError(); onSetup(real.toCharArray(), duress.toCharArray()) },
            Modifier.fillMaxWidth(),
            enabled = canSubmit,
        )
        Text(
            "Minimum ${SafeAuthRepository.MIN_PIN} characters (digits or a passphrase). " +
                "The two PINs must differ. Failed entries are throttled automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FRSecondaryButton("CANCEL", onCancel, Modifier.fillMaxWidth())
    }
}

@Composable
private fun ChangePinsScreen(
    pinError: String?,
    onDismissError: () -> Unit,
    onChange: (CharArray, CharArray, CharArray) -> Unit,
    onBack: () -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var newReal by remember { mutableStateOf("") }
    var newReal2 by remember { mutableStateOf("") }
    var newDuress by remember { mutableStateOf("") }
    var newDuress2 by remember { mutableStateOf("") }
    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(FRSpacing.md),
        verticalArrangement = Arrangement.spacedBy(FRSpacing.sm),
    ) {
        Text("CHANGE VAULT PINS", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            value = current,
            onValueChange = { current = it; onDismissError() },
            label = { Text("Current Vault PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = pinFieldOptions(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = frTextFieldColors(),
        )
        OutlinedTextField(
            value = newReal,
            onValueChange = { newReal = it },
            label = { Text("New Vault PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = pinFieldOptions(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = frTextFieldColors(),
        )
        OutlinedTextField(
            value = newReal2,
            onValueChange = { newReal2 = it },
            label = { Text("Confirm New Vault PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = pinFieldOptions(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = frTextFieldColors(),
        )
        OutlinedTextField(
            value = newDuress,
            onValueChange = { newDuress = it },
            label = { Text("New Duress PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = pinFieldOptions(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = frTextFieldColors(),
        )
        OutlinedTextField(
            value = newDuress2,
            onValueChange = { newDuress2 = it },
            label = { Text("Confirm New Duress PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = pinFieldOptions(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = frTextFieldColors(),
        )
        if (pinError != null) {
            Text(pinError, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        val ok = current.length >= SafeAuthRepository.MIN_PIN &&
            newReal.length >= SafeAuthRepository.MIN_PIN && newReal == newReal2 &&
            newDuress.length >= SafeAuthRepository.MIN_PIN && newDuress == newDuress2 &&
            newReal != newDuress
        FRPrimaryButton(
            "SAVE PINS",
            { onDismissError(); onChange(current.toCharArray(), newReal.toCharArray(), newDuress.toCharArray()) },
            Modifier.fillMaxWidth(),
            enabled = ok,
        )
        FRSecondaryButton("CANCEL", onBack, Modifier.fillMaxWidth())
    }
}

// ------------------------------------------------------------------
// Note editor
// ------------------------------------------------------------------

@Composable
private fun NoteEditorScreen(
    draft: NoteDraft,
    onEdit: (String, String) -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit,
) {
    var title by remember(draft.id) { mutableStateOf(draft.title) }
    var body by remember(draft.id) { mutableStateOf(draft.body) }
    var confirmDelete by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(FRSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FRSecondaryButton("SAVE & CLOSE", onClose, Modifier.weight(1f))
            if (draft.id != null) {
                Spacer(Modifier.width(FRSpacing.sm))
                RowActionButton("DELETE", MaterialTheme.colorScheme.error) { confirmDelete = true }
            }
        }
        Spacer(Modifier.height(FRSpacing.sm))
        val kb = KeyboardOptions(autoCorrectEnabled = false)
        OutlinedTextField(
            value = title,
            onValueChange = { title = it; onEdit(it, body) },
            label = { Text("TITLE") },
            singleLine = true,
            keyboardOptions = kb,
            modifier = Modifier.fillMaxWidth(),
            colors = frTextFieldColors(),
        )
        Spacer(Modifier.height(FRSpacing.xs))
        OutlinedTextField(
            value = body,
            onValueChange = { body = it; onEdit(title, it) },
            label = { Text("NOTE") },
            keyboardOptions = kb,
            modifier = Modifier.fillMaxWidth().weight(1f),
            colors = frTextFieldColors(),
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete note?",
            body = "The note and its key will be permanently destroyed.",
            confirmText = "DELETE",
            destructive = true,
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false },
        )
    }
}

// ------------------------------------------------------------------
// Document viewers
// ------------------------------------------------------------------

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PagesView(doc: OpenedDocument.Pages) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) {
            maxWidth.roundToPx().coerceAtMost(2048) // bounded render width keeps memory predictable
        }
        val cache = remember(doc) { LruCache<Int, Bitmap>(8) }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(FRSpacing.sm),
            contentPadding = PaddingValues(FRSpacing.md),
        ) {
            items(doc.pageCount) { i ->
                val bmp = produceState<Bitmap?>(null, i, widthPx) {
                    val cached = cache.get(i)
                    if (cached != null) {
                        value = cached
                    } else {
                        value = try {
                            doc.render(i, widthPx).also { cache.put(i, it) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
                val page = bmp.value
                if (page != null) {
                    Image(
                        page.asImageBitmap(),
                        contentDescription = "Page ${i + 1}",
                        modifier = Modifier.fillMaxWidth().border(1.dp, LocalExtendedColors.current.divider),
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(400.dp)
                            .border(1.dp, LocalExtendedColors.current.divider),
                    )
                }
            }
        }
    }
}

@Composable
private fun TextView(doc: OpenedDocument.Text) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(FRSpacing.md),
        verticalArrangement = Arrangement.spacedBy(FRSpacing.sm),
    ) {
        items(doc.blocks) { b ->
            val style = when (b.style) {
                TextStyle.H1 -> MaterialTheme.typography.headlineMedium
                TextStyle.H2 -> MaterialTheme.typography.titleLarge
                TextStyle.H3 -> MaterialTheme.typography.titleMedium
                TextStyle.BODY -> MaterialTheme.typography.bodyLarge
            }
            Text(b.text, style = style)
        }
    }
}

// ------------------------------------------------------------------
// Formatting helpers
// ------------------------------------------------------------------

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

private fun formatDate(ms: Long): String =
    if (ms <= 0) "" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

private fun libraryFormat(item: LibraryItem): String = when {
    item.mimeType == "application/pdf" || item.name.endsWith(".pdf", true) -> "PDF"
    item.mimeType.contains("wordprocessingml") || item.name.endsWith(".docx", true) -> "DOCX"
    item.mimeType.contains("fictionbook") || item.name.lowercase().endsWith(".fb2") ||
        item.name.lowercase().endsWith(".fb2.zip") -> "FB2"
    else -> "FILE"
}

private fun nameFormat(name: String): String = when {
    name.endsWith(".pdf", true) -> "PDF"
    name.endsWith(".docx", true) -> "DOCX"
    name.endsWith(".fb2", true) || name.lowercase().endsWith(".fb2.zip") -> "FB2"
    else -> "DOC"
}
