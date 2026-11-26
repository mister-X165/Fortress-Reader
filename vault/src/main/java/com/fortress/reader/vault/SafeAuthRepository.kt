package com.fortress.reader.vault

import com.fortress.reader.security.PasswordCrypto
import com.fortress.reader.security.UserFacingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface UnlockResult {
    /** Real password matched; vault is accessible. */
    data object Real : UnlockResult

    /** Duress password matched; vault contents have been irreversibly destroyed. */
    data object Duress : UnlockResult

    /** Neither matched. */
    data object Failed : UnlockResult

    /** Too many failures; the safe refuses verification until the backoff expires. */
    data class Locked(val remainingMs: Long) : UnlockResult
}

/**
 * Manages the digital-safe PIN / password and the duress (coercion) password.
 *
 * - First-time setup requires both a real PIN and a different duress PIN
 *   ([MIN_PIN] characters minimum).
 * - Unlock is constant-time for the two verifiers; the caller only sees
 *   Real / Duress / Failed / Locked.
 * - Failed attempts are throttled: 30 s after the third failure, doubling
 *   per failure, capped at 24 h. The backoff is checked before any PBKDF2
 *   work is done.
 * - On Duress the repository triggers a full crypto-erase of documents,
 *   photos and notes before returning, so the UI always shows an empty safe.
 */
class SafeAuthRepository(
    private val authDao: SafeAuthDao,
    private val wipeCallback: suspend () -> Unit,
) {
    suspend fun isSetup(): Boolean = withContext(Dispatchers.IO) {
        authDao.get() != null
    }

    /**
     * First-time configuration. Both PINs must meet the minimum length and differ.
     * Existing data (if any) is left intact — call only on a fresh install or after intentional wipe.
     */
    suspend fun setup(realPin: CharArray, duressPin: CharArray) = withContext(Dispatchers.IO) {
        if (realPin.size < MIN_PIN) throw UserFacingException("PIN must be at least $MIN_PIN characters")
        if (duressPin.size < MIN_PIN) throw UserFacingException("Duress PIN must be at least $MIN_PIN characters")
        if (realPin.contentEquals(duressPin)) {
            throw UserFacingException("Real PIN and duress PIN must be different")
        }
        val real = PasswordCrypto.createVerifier(realPin)
        val duress = PasswordCrypto.createVerifier(duressPin)
        try {
            authDao.upsert(
                SafeAuthEntity(
                    realSalt = real.salt,
                    realHash = real.hash,
                    duressSalt = duress.salt,
                    duressHash = duress.hash,
                    setupAt = System.currentTimeMillis(),
                ),
            )
        } finally {
            real.wipe()
            duress.wipe()
            PasswordCrypto.wipe(realPin)
            PasswordCrypto.wipe(duressPin)
        }
    }

    /**
     * Change both PINs (requires the current real PIN). Resets the failure counter.
     * Does not affect stored ciphertext.
     */
    suspend fun changePins(currentReal: CharArray, newReal: CharArray, newDuress: CharArray) =
        withContext(Dispatchers.IO) {
            val entity = authDao.get() ?: throw UserFacingException("Safe is not set up")
            if (!PasswordCrypto.matches(currentReal, entity.realSalt, entity.realHash)) {
                PasswordCrypto.wipe(currentReal)
                throw UserFacingException("Current PIN is incorrect")
            }
            PasswordCrypto.wipe(currentReal)
            setup(newReal, newDuress)
        }

    /**
     * Attempt unlock. On Duress the wipeCallback is invoked first (crypto-erase everything).
     * Callers must treat Real and Duress identically from a UI perspective after the fact.
     */
    suspend fun unlock(pin: CharArray): UnlockResult = withContext(Dispatchers.IO) {
        val entity = authDao.get() ?: run {
            PasswordCrypto.wipe(pin)
            return@withContext UnlockResult.Failed
        }
        val now = System.currentTimeMillis()
        if (entity.lockedUntil > now) {
            PasswordCrypto.wipe(pin)
            return@withContext UnlockResult.Locked(entity.lockedUntil - now)
        }
        val isReal = PasswordCrypto.matches(pin, entity.realSalt, entity.realHash)
        val isDuress = if (!isReal) {
            PasswordCrypto.matches(pin, entity.duressSalt, entity.duressHash)
        } else false
        PasswordCrypto.wipe(pin)
        when {
            isReal -> {
                resetAttempts(entity)
                UnlockResult.Real
            }
            isDuress -> {
                resetAttempts(entity)
                // Irreversible destruction of all safe contents.
                wipeCallback()
                UnlockResult.Duress
            }
            else -> {
                registerFailure(entity, now)
                UnlockResult.Failed
            }
        }
    }

    /** Remaining lock time in ms, or 0 when the safe accepts attempts again. */
    suspend fun remainingLockMs(): Long = withContext(Dispatchers.IO) {
        val entity = authDao.get() ?: return@withContext 0L
        (entity.lockedUntil - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    private suspend fun resetAttempts(entity: SafeAuthEntity) {
        authDao.upsert(entity.copy(failedAttempts = 0, lockedUntil = 0))
    }

    private suspend fun registerFailure(entity: SafeAuthEntity, now: Long) {
        val attempts = entity.failedAttempts + 1
        val lock = lockoutFor(attempts)
        authDao.upsert(
            entity.copy(
                failedAttempts = attempts,
                lockedUntil = if (lock > 0) now + lock else 0,
            ),
        )
    }

    companion object {
        const val MIN_PIN = 6
        private const val MAX_LOCK_MS = 24L * 60 * 60 * 1000

        /** 30 s after the 3rd failure, doubling per failure, capped at 24 h. */
        fun lockoutFor(attempts: Int): Long {
            if (attempts < 3) return 0L
            val shift = (attempts - 3).coerceAtMost(11)
            return (30_000L shl shift).coerceAtMost(MAX_LOCK_MS)
        }
    }
}