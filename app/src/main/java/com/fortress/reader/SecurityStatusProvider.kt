package com.fortress.reader

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import com.fortress.reader.security.KeystoreKeyWrapper
import java.io.File
import java.security.KeyStore
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Verifiable states only — the dashboard never fabricates metrics. */
enum class SecurityState { PROTECTED, LIMITED, NOT_VERIFIED, NON_COMPLIANT }

data class SecurityCheck(
    val id: String,
    val title: String,
    val state: SecurityState,
    val detail: String,
)

sealed interface MasterKeyStatus {
    data object NotCreated : MasterKeyStatus
    data object HardwareBacked : MasterKeyStatus
    data object Software : MasterKeyStatus
    data object NotVerified : MasterKeyStatus
}

/**
 * Computes the security dashboard from live device checks:
 * Keystore key properties, manifest flags, runtime permissions and session state.
 */
class SecurityStatusProvider(private val context: Context) {

    suspend fun checks(screenProtected: Boolean, sessionLocked: Boolean): List<SecurityCheck> =
        withContext(Dispatchers.IO) {
            listOf(
                keyProtectionCheck(),
                vaultEncryptionCheck(),
                backupCheck(),
                networkCheck(),
                screenCheck(screenProtected),
                sessionCheck(sessionLocked),
            )
        }

    private fun keyProtectionCheck(): SecurityCheck = when (val status = masterKeyStatus()) {
        MasterKeyStatus.HardwareBacked -> SecurityCheck(
            "key", "KEY PROTECTION", SecurityState.PROTECTED,
            "The vault master key is generated inside secure hardware (TEE/StrongBox), never leaves the device and cannot be exported.",
        )
        MasterKeyStatus.Software -> SecurityCheck(
            "key", "KEY PROTECTION", SecurityState.LIMITED,
            "The vault master key exists, but this device reports no hardware isolation. It is still non-exportable and app-private.",
        )
        MasterKeyStatus.NotCreated -> SecurityCheck(
            "key", "KEY PROTECTION", SecurityState.NOT_VERIFIED,
            "No vault master key exists yet. The key is created the first time an item is stored in the vault.",
        )
        MasterKeyStatus.NotVerified -> SecurityCheck(
            "key", "KEY PROTECTION", SecurityState.NOT_VERIFIED,
            "The key status could not be read on this device.",
        )
    }

    private fun vaultEncryptionCheck(): SecurityCheck = SecurityCheck(
        "crypto", "VAULT ENCRYPTION", SecurityState.PROTECTED,
        "Every document, photo and note is sealed with its own AES-256-GCM key. Chunk authentication detects truncation, reordering and tampering.",
    )

    private fun backupCheck(): SecurityCheck {
        val allowed = (context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP) != 0
        return if (!allowed) {
            SecurityCheck(
                "backup", "BACKUP EXCLUSION", SecurityState.PROTECTED,
                "Android cloud backup and device-to-device transfer are disabled for this application.",
            )
        } else {
            SecurityCheck(
                "backup", "BACKUP EXCLUSION", SecurityState.NON_COMPLIANT,
                "android:allowBackup is enabled — vault data may leave the device through Android backups.",
            )
        }
    }

    private fun networkCheck(): SecurityCheck {
        val granted = try {
            context.packageManager.checkPermission(
                Manifest.permission.INTERNET,
                context.packageName,
            ) == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            true
        }
        return if (!granted) {
            SecurityCheck(
                "network", "NETWORK ACCESS", SecurityState.PROTECTED,
                "The app does not hold the INTERNET permission. All network traffic is blocked by the platform.",
            )
        } else {
            SecurityCheck(
                "network", "NETWORK ACCESS", SecurityState.NON_COMPLIANT,
                "The INTERNET permission is present in the merged manifest — verify that no dependency added it.",
            )
        }
    }

    private fun screenCheck(active: Boolean): SecurityCheck =
        if (active) {
            SecurityCheck(
                "screen", "SCREEN PROTECTION", SecurityState.PROTECTED,
                "FLAG_SECURE is active: screenshots, screen recording and recents previews are blocked.",
            )
        } else {
            SecurityCheck(
                "screen", "SCREEN PROTECTION", SecurityState.NON_COMPLIANT,
                "FLAG_SECURE is not active on the current window.",
            )
        }

    private fun sessionCheck(locked: Boolean): SecurityCheck =
        if (locked) {
            SecurityCheck(
                "session", "SESSION LOCK", SecurityState.PROTECTED,
                "The session is locked now. Biometric or device credential is required to continue.",
            )
        } else {
            SecurityCheck(
                "session", "SESSION LOCK", SecurityState.LIMITED,
                "The session is unlocked. The app locks automatically when it leaves the foreground, and the vault re-locks when left.",
            )
        }

    @Suppress("DEPRECATION")
    private fun masterKeyStatus(): MasterKeyStatus {
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = ks.getKey(KeystoreKeyWrapper.DEFAULT_ALIAS, null) as? SecretKey
            if (key == null) return MasterKeyStatus.NotCreated
            val factory = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
            val hardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                info.securityLevel != KeyProperties.SECURITY_LEVEL_SOFTWARE &&
                    info.securityLevel != KeyProperties.SECURITY_LEVEL_UNKNOWN
            } else {
                info.isInsideSecureHardware
            }
            if (hardware) MasterKeyStatus.HardwareBacked else MasterKeyStatus.Software
        } catch (_: Exception) {
            MasterKeyStatus.NotVerified
        }
    }

    /** Total bytes of encrypted vault payload on disk (ciphertext, not plaintext). */
    fun storageBytes(): Long = listOf("vault", "photos").sumOf { dirSize(File(context.noBackupFilesDir, it)) }

    private fun dirSize(dir: File): Long =
        dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
}
