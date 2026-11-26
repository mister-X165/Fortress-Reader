package com.fortress.reader.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Password / PIN handling for the digital safe.
 *
 * - Verification uses a high-iteration PBKDF2-HMAC-SHA256 hash (salted).
 * - A separate random "verification token" is never stored; we only keep
 *   salt + derived verifier. Timing-safe comparison is used.
 * - Duress and real passwords produce independent verifiers; the app cannot
 *   distinguish which one matched except by which verifier succeeds.
 */
object PasswordCrypto {
    /**
     * OWASP (2023) recommends 600k iterations for PBKDF2-HMAC-SHA256.
     * One verification takes roughly 0.3-1 s on modern phone hardware,
     * which also acts as an inline cost per guess for on-device brute force.
     */
    private const val ITERATIONS = 600_000
    private const val KEY_LEN_BITS = 256
    private const val SALT_LEN = 16
    private val rng = SecureRandom()

    data class Verifier(val salt: ByteArray, val hash: ByteArray) {
        fun wipe() {
            salt.fill(0)
            hash.fill(0)
        }
    }

    fun newSalt(): ByteArray = ByteArray(SALT_LEN).also { rng.nextBytes(it) }

    /** Derive a verification hash for the given password + salt. */
    fun deriveVerifier(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_LEN_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun createVerifier(password: CharArray): Verifier {
        val salt = newSalt()
        val hash = deriveVerifier(password, salt)
        return Verifier(salt, hash)
    }

    /** Constant-time comparison of password against stored verifier. */
    fun matches(password: CharArray, salt: ByteArray, expectedHash: ByteArray): Boolean {
        val actual = deriveVerifier(password, salt)
        return try {
            MessageDigest.isEqual(actual, expectedHash)
        } finally {
            actual.fill(0)
        }
    }

    /** Wipe a char array (best-effort; strings are immutable). */
    fun wipe(chars: CharArray) {
        Arrays.fill(chars, '\u0000')
    }
}
