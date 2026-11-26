package com.fortress.reader

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fortress.reader.document.DocumentFormat
import com.fortress.reader.document.OpenedDocument
import com.fortress.reader.document.RendererRegistry
import com.fortress.reader.security.SecureTemp
import com.fortress.reader.security.UserFacingException
import com.fortress.reader.vault.DecryptedFile
import com.fortress.reader.vault.ImportInfo
import com.fortress.reader.vault.LibraryItem
import com.fortress.reader.vault.LibraryRepository
import com.fortress.reader.vault.NoteRepository
import com.fortress.reader.vault.PhotoRepository
import com.fortress.reader.vault.SafeAuthRepository
import com.fortress.reader.vault.UnlockResult
import com.fortress.reader.vault.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Top-level navigation after the app (biometric) lock is cleared. */
sealed interface Screen {
    data object Library : Screen
    data object SafeLocked : Screen          // PIN entry for the digital safe
    data object SafeSetup : Screen           // first-time PIN + duress setup
    data object SafeHome : Screen            // documents / photos / notes inside the safe
    data object SafeSettings : Screen
    data object SecurityDashboard : Screen   // verified device states
    data object Loading : Screen
    data object NoteEditor : Screen
    class Viewer(val title: String, val document: OpenedDocument) : Screen
    class PhotoViewer(val title: String, val file: File) : Screen
    class Failure(val message: String) : Screen
    data class ImportConfirm(val pending: PendingImport) : Screen
}

/** Where the picked file came from / is going. */
enum class ImportTarget { VAULT_DOCUMENT, VAULT_PHOTO, TEMPORARY_VIEW }

/** Storage mode selected on the Import Document screen. */
enum class ImportMode { TO_VAULT, TEMPORARY }

data class PendingImport(
    val uri: Uri,
    val target: ImportTarget,
    val name: String,
    val sizeBytes: Long,
    val formatLabel: String,
    val preselected: ImportMode,
)

data class BusyState(val title: String, val progress: Float?)

data class NoteDraft(val id: String?, val title: String, val body: String, val dirty: Boolean = false)

@HiltViewModel
class AppViewModel @Inject constructor(
    private val repo: VaultRepository,
    private val notes: NoteRepository,
    private val photos: PhotoRepository,
    private val safeAuth: SafeAuthRepository,
    private val library: LibraryRepository,
    private val temp: SecureTemp,
    private val registry: RendererRegistry,
    private val securityStatus: SecurityStatusProvider,
) : ViewModel() {

    // ---- Level 1: ordinary library (synced with phone files) ----
    val libraryItems = library.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- Level 2: digital safe ----
    val documents = repo.documents().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val noteList = notes.notes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val photoList = photos.photos().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Plaintext-equivalent payload tracked by the vault (documents + photos). */
    val vaultUsageBytes: StateFlow<Long> =
        combine(documents, photoList) { d, p -> d.sumOf { it.sizeBytes } + p.sumOf { it.sizeBytes } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    private val _screen = MutableStateFlow<Screen>(Screen.Library)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _locked = MutableStateFlow(true)          // app-level (biometric) lock
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _safeUnlocked = MutableStateFlow(false)   // session unlock of the safe
    val safeUnlocked: StateFlow<Boolean> = _safeUnlocked.asStateFlow()

    private val _safeSetupDone = MutableStateFlow(false)
    val safeSetupDone: StateFlow<Boolean> = _safeSetupDone.asStateFlow()

    private val _draft = MutableStateFlow<NoteDraft?>(null)
    val draft: StateFlow<NoteDraft?> = _draft.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _pinError = MutableStateFlow<String?>(null)
    val pinError: StateFlow<String?> = _pinError.asStateFlow()

    private val _busy = MutableStateFlow<BusyState?>(null)
    val busy: StateFlow<BusyState?> = _busy.asStateFlow()

    private val _screenProtected = MutableStateFlow(false)
    val screenProtected: StateFlow<Boolean> = _screenProtected.asStateFlow()

    private val _checks = MutableStateFlow<List<SecurityCheck>?>(null)
    val checks: StateFlow<List<SecurityCheck>?> = _checks.asStateFlow()

    private var openDoc: OpenedDocument? = null
    private var openFile: File? = null
    private var generation = 0

    init {
        viewModelScope.launch {
            runCatching { repo.purgeOrphans() }
            runCatching { photos.purgeOrphans() }
            _safeSetupDone.value = safeAuth.isSetup()
            library.refresh()
        }
    }

    fun unlock() { _locked.value = false }
    fun dismissMessage() { _message.value = null }
    fun dismissPinError() { _pinError.value = null }

    /** Called once by the Activity after FLAG_SECURE is applied to the window. */
    fun markScreenProtectionActive() { _screenProtected.value = true }

    /** Saves drafts, closes open files, locks both app and safe. */
    fun lock() {
        generation++
        _draft.value?.let(::persist)
        _draft.value = null
        releaseDocument()
        _safeUnlocked.value = false
        _screen.value = Screen.Library
        _locked.value = true
    }

    // ------------------------------------------------------------------
    // Bottom navigation
    // ------------------------------------------------------------------
    fun goLibrary() {
        _safeUnlocked.value = false
        _screen.value = Screen.Library
    }

    fun goVault() = enterSafe()

    fun goSecurity() = openDashboard()

    private fun openDashboard() {
        _screen.value = Screen.SecurityDashboard
        refreshChecks()
    }

    fun refreshChecks() {
        viewModelScope.launch {
            _checks.value = securityStatus.checks(
                screenProtected = _screenProtected.value,
                sessionLocked = _locked.value,
            )
        }
    }

    // ------------------------------------------------------------------
    // Level 1 – Library (phone files)
    // ------------------------------------------------------------------
    fun refreshLibrary() {
        viewModelScope.launch { library.refresh() }
    }

    /** Library browsing opens items directly as a temporary view. */
    fun openLibraryItem(item: LibraryItem) = open("Opening document") {
        repo.copyToTemp(item.uri) { done, total -> reportProgress("Opening document", done, total) }
    }

    // ------------------------------------------------------------------
    // Import flow: inspect -> confirm -> execute
    // ------------------------------------------------------------------
    fun prepareImport(uri: Uri, target: ImportTarget) {
        viewModelScope.launch {
            try {
                val info: ImportInfo = when (target) {
                    ImportTarget.VAULT_DOCUMENT, ImportTarget.TEMPORARY_VIEW -> repo.inspectDocument(uri)
                    ImportTarget.VAULT_PHOTO -> photos.inspectPhoto(uri)
                }
                val cap = if (target == ImportTarget.VAULT_PHOTO) {
                    PhotoRepository.MAX_BYTES
                } else {
                    VaultRepository.MAX_BYTES
                }
                if (info.sizeBytes > cap) {
                    _message.value = if (target == ImportTarget.VAULT_PHOTO) {
                        "The file is larger than 50 MB"
                    } else {
                        "The file is larger than 100 MB"
                    }
                    return@launch
                }
                if (info.formatLabel == "UNSUPPORTED") {
                    _message.value = if (target == ImportTarget.VAULT_PHOTO) {
                        "Unsupported image. Supported: JPEG, PNG, WEBP, HEIF, GIF."
                    } else {
                        "Unsupported file. Supported formats: ${DocumentFormat.SUPPORTED_LABEL}"
                    }
                    return@launch
                }
                val mode = if (target == ImportTarget.TEMPORARY_VIEW) ImportMode.TEMPORARY else ImportMode.TO_VAULT
                _screen.value = Screen.ImportConfirm(
                    PendingImport(uri, target, info.name, info.sizeBytes, info.formatLabel, mode),
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _message.value = e.userMessage("Could not read this file.")
            }
        }
    }

    fun confirmImport(mode: ImportMode) {
        val pending = (_screen.value as? Screen.ImportConfirm)?.pending ?: return
        if (pending.target != ImportTarget.VAULT_PHOTO && mode == ImportMode.TEMPORARY) {
            open("Opening document") {
                repo.copyToTemp(pending.uri) { done, total -> reportProgress("Opening document", done, total) }
            }
        } else {
            // Vault targets always import; documents picked for temporary viewing may still be imported.
            // Return to the context the user came from; the success message is shown only
            // after encryption has actually completed.
            _screen.value = if (_safeUnlocked.value) Screen.SafeHome else Screen.Library
            if (pending.target == ImportTarget.VAULT_PHOTO) {
                importPhotoInternal(pending.uri)
            } else {
                importDocInternal(pending.uri)
            }
        }
    }

    fun cancelImport() {
        val pending = (_screen.value as? Screen.ImportConfirm)?.pending
        _screen.value = if (pending?.target == ImportTarget.TEMPORARY_VIEW) Screen.Library else Screen.SafeHome
    }

    private fun reportProgress(title: String, done: Long, total: Long) {
        _busy.value = BusyState(title, if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null)
    }

    private fun importDocInternal(uri: Uri) {
        viewModelScope.launch {
            _busy.value = BusyState("Encrypting into vault", null)
            try {
                repo.importToVault(uri) { done, total -> reportProgress("Encrypting into vault", done, total) }
                _message.value = "Imported to vault. Encryption complete."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _message.value = e.userMessage("Could not import this file.")
            } finally {
                _busy.value = null
            }
        }
    }

    private fun importPhotoInternal(uri: Uri) {
        viewModelScope.launch {
            _busy.value = BusyState("Encrypting into vault", null)
            try {
                photos.importPhoto(uri) { done, total -> reportProgress("Encrypting into vault", done, total) }
                _message.value = "Imported to vault. Encryption complete."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _message.value = e.userMessage("Could not import this photo.")
            } finally {
                _busy.value = null
            }
        }
    }

    // ------------------------------------------------------------------
    // Level 2 – Digital safe
    // ------------------------------------------------------------------
    fun enterSafe() {
        viewModelScope.launch {
            if (!safeAuth.isSetup()) {
                _screen.value = Screen.SafeSetup
            } else if (_safeUnlocked.value) {
                _screen.value = Screen.SafeHome
            } else {
                _screen.value = Screen.SafeLocked
            }
        }
    }

    fun leaveSafe() {
        // Re-lock the safe when leaving; documents stay encrypted at rest.
        _safeUnlocked.value = false
        _screen.value = Screen.Library
    }

    fun setupSafe(realPin: CharArray, duressPin: CharArray) {
        viewModelScope.launch {
            try {
                safeAuth.setup(realPin, duressPin)
                _safeSetupDone.value = true
                _safeUnlocked.value = true
                _screen.value = Screen.SafeHome
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _pinError.value = e.userMessage("Could not set up the safe.")
            }
        }
    }

    fun unlockSafe(pin: CharArray) {
        viewModelScope.launch {
            try {
                when (val result = safeAuth.unlock(pin)) {
                    UnlockResult.Real, UnlockResult.Duress -> {
                        // Duress already wiped data; UI looks identical (empty safe).
                        _safeUnlocked.value = true
                        _pinError.value = null
                        _screen.value = Screen.SafeHome
                    }
                    is UnlockResult.Locked -> {
                        _pinError.value = "Too many failed attempts. Locked for ${formatRemaining(result.remainingMs)}."
                    }
                    UnlockResult.Failed -> {
                        _pinError.value = "Incorrect PIN"
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _pinError.value = e.userMessage("Unlock failed")
            }
        }
    }

    private fun formatRemaining(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(1)
        return when {
            s >= 3600 -> "${s / 3600} h ${(s % 3600) / 60} min"
            s >= 60 -> "${s / 60} min"
            else -> "$s s"
        }
    }

    fun openSafeSettings() { _screen.value = Screen.SafeSettings }
    fun closeSafeSettings() { _screen.value = Screen.SafeHome }

    fun changeSafePins(current: CharArray, newReal: CharArray, newDuress: CharArray) {
        viewModelScope.launch {
            try {
                safeAuth.changePins(current, newReal, newDuress)
                _message.value = "PIN changed"
                _screen.value = Screen.SafeHome
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _pinError.value = e.userMessage("Could not change PIN")
            }
        }
    }

    // documents in safe
    fun openFromVault(id: String) = open("Decrypting document") { repo.decryptToTemp(id) }
    fun deleteDocument(id: String) { viewModelScope.launch { repo.delete(id) } }

    // photos in safe
    fun openPhoto(id: String) {
        generation++
        val mine = generation
        releaseDocument()
        _screen.value = Screen.Loading
        _busy.value = BusyState("Decrypting photo", null)
        viewModelScope.launch {
            var file: File? = null
            try {
                val f = photos.decryptToTemp(id)
                file = f.file
                if (mine != generation) { temp.wipe(f.file); return@launch }
                openFile = f.file
                _screen.value = Screen.PhotoViewer(f.displayName, f.file)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                file?.let(temp::wipe)
                if (mine == generation) _screen.value = Screen.Failure(e.userMessage("This photo could not be opened."))
            } finally {
                _busy.value = null
            }
        }
    }

    fun deletePhoto(id: String) { viewModelScope.launch { photos.delete(id) } }

    fun closeDocument() {
        generation++
        releaseDocument()
        // return to whichever area we came from
        _screen.value = if (_safeUnlocked.value) Screen.SafeHome else Screen.Library
    }

    private fun open(title: String, prepare: suspend () -> DecryptedFile) {
        generation++
        val mine = generation
        releaseDocument()
        _screen.value = Screen.Loading
        _busy.value = BusyState(title, null)
        viewModelScope.launch {
            var file: File? = null
            var doc: OpenedDocument? = null
            try {
                val f = prepare()
                file = f.file
                doc = registry.open(f.file)
                if (mine != generation) {
                    runCatching { doc.close() }; temp.wipe(f.file); return@launch
                }
                openFile = f.file; openDoc = doc
                _screen.value = Screen.Viewer(f.displayName, doc)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                runCatching { doc?.close() }; file?.let(temp::wipe)
                if (mine == generation) _screen.value = Screen.Failure(e.userMessage("This document could not be opened."))
            } finally {
                _busy.value = null
            }
        }
    }

    private fun releaseDocument() {
        runCatching { openDoc?.close() }; openDoc = null
        openFile?.let(temp::wipe); openFile = null
    }

    // notes
    fun newNote() { _draft.value = NoteDraft(null, "", ""); _screen.value = Screen.NoteEditor }

    fun openNote(id: String) {
        viewModelScope.launch {
            try {
                val n = notes.get(id)
                _draft.value = NoteDraft(n.id, n.title, n.body)
                _screen.value = Screen.NoteEditor
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _message.value = e.userMessage("Could not open the note.")
            }
        }
    }

    fun editDraft(title: String, body: String) {
        _draft.update { it?.copy(title = title, body = body, dirty = true) }
    }

    fun closeNote() {
        val d = _draft.value
        _draft.value = null
        _screen.value = Screen.SafeHome
        if (d != null) persist(d)
    }

    fun deleteCurrentNote() {
        val id = _draft.value?.id
        _draft.value = null
        _screen.value = Screen.SafeHome
        if (id != null) viewModelScope.launch { notes.delete(id) }
    }

    fun deleteNote(id: String) { viewModelScope.launch { notes.delete(id) } }

    private fun persist(d: NoteDraft) {
        if (!d.dirty || (d.id == null && d.title.isBlank() && d.body.isBlank())) return
        viewModelScope.launch {
            try {
                // The save must complete even when the scope is cancelled by locking.
                withContext(NonCancellable) { notes.save(d.id, d.title, d.body) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _message.value = e.userMessage("Could not save the note.")
            }
        }
    }

    private fun Throwable.userMessage(default: String) =
        if (this is UserFacingException) message ?: default else default

    override fun onCleared() { releaseDocument() }
}
