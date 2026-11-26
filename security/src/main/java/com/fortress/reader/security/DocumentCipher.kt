package com.fortress.reader.security

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Streaming AES-256-GCM container.
 *
 * Format: MAGIC(4) | nonce(8) | repeat{ ctLen(4) | ciphertext+tag }
 * Each 64 KiB chunk is sealed with IV = nonce || chunkCounter and AAD = [isLastChunk].
 * Reordering, truncation and appending are therefore detected by the GCM tag.
 * Pure javax.crypto: unit-testable on the JVM.
 */
object DocumentCipher {
    private const val MAGIC = 0x46525331 // "FRS1"
    const val CHUNK_SIZE = 64 * 1024
    private const val TAG_BYTES = 16
    private val rng = SecureRandom()

    fun newDek(): ByteArray = ByteArray(32).also { rng.nextBytes(it) }

    /** @return number of plaintext bytes processed. */
    fun encrypt(
        input: InputStream,
        output: OutputStream,
        dek: ByteArray,
        maxPlainBytes: Long = Long.MAX_VALUE,
        onProgress: (plainBytesProcessed: Long) -> Unit = {},
    ): Long {
        require(dek.size == 32) { "DEK must be 32 bytes" }
        val out = DataOutputStream(output)
        val nonce = ByteArray(8).also { rng.nextBytes(it) }
        out.writeInt(MAGIC); out.write(nonce)
        val key = SecretKeySpec(dek, "AES")
        val src = PushbackInputStream(input, 1)
        val buf = ByteArray(CHUNK_SIZE)
        var counter = 0
        var total = 0L
        while (true) {
            val n = readFully(src, buf)
            total += n
            if (total > maxPlainBytes) throw IOException("File too large")
            var last = n < CHUNK_SIZE
            if (!last) {
                val b = src.read()
                if (b < 0) last = true else src.unread(b)
            }
            val ct = crypt(Cipher.ENCRYPT_MODE, key, nonce, counter, last, buf, n)
            out.writeInt(ct.size); out.write(ct)
            if (last) break
            counter++
            onProgress(total)
        }
        buf.fill(0)
        out.flush()
        return total
    }

    /** @throws GeneralSecurityException / IOException on wrong key, tampering or truncation. */
    fun decrypt(input: InputStream, output: OutputStream, dek: ByteArray): Long {
        require(dek.size == 32) { "DEK must be 32 bytes" }
        val src = PushbackInputStream(input, 1)
        val din = DataInputStream(src)
        if (din.readInt() != MAGIC) throw IOException("Unrecognized container")
        val nonce = ByteArray(8).also { din.readFully(it) }
        val key = SecretKeySpec(dek, "AES")
        var counter = 0
        var total = 0L
        while (true) {
            val len = din.readInt()
            if (len < TAG_BYTES || len > CHUNK_SIZE + TAG_BYTES) throw IOException("Corrupt chunk length")
            val ct = ByteArray(len).also { din.readFully(it) }
            val b = src.read()
            val last = b < 0
            if (!last) src.unread(b)
            val pt = crypt(Cipher.DECRYPT_MODE, key, nonce, counter, last, ct, ct.size)
            output.write(pt)
            total += pt.size
            pt.fill(0)
            if (last) break
            counter++
        }
        output.flush()
        return total
    }

    private fun crypt(mode: Int, key: SecretKeySpec, nonce: ByteArray, counter: Int, last: Boolean, data: ByteArray, len: Int): ByteArray {
        val iv = ByteBuffer.allocate(12).put(nonce).putInt(counter).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(byteArrayOf(if (last) 1 else 0))
        try {
            return cipher.doFinal(data, 0, len)
        } catch (e: GeneralSecurityException) {
            throw GeneralSecurityException("Authentication failed")
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }
}

fun ByteArray.wipe() = fill(0)
