package com.fortress.reader.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Abstraction over the platform key store. A future Fortress Mobile build supplies its own
 * implementation (PlatformSecurityProvider) without touching the rest of the app.
 */
interface KeyWrapper {
    fun wrap(plain: ByteArray): ByteArray
    fun unwrap(blob: ByteArray): ByteArray
}

/** Non-exportable AES-256-GCM master key in Android Keystore (StrongBox when available). */
class KeystoreKeyWrapper(private val alias: String = DEFAULT_ALIAS) : KeyWrapper {

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return try { generate(strongBox = true) } catch (_: Exception) { generate(strongBox = false) }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }.generateKey()
    }

    override fun wrap(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(plain) // 12-byte IV prefix
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12))) }
        return c.doFinal(blob, 12, blob.size - 12)
    }

    companion object {
        /** Shared with SecurityStatusProvider for honest hardware-backed verification. */
        const val DEFAULT_ALIAS = "fortress_master_v1"
    }
}
