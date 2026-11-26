package com.fortress.reader.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class DocumentCipherTest {
    private val dek = DocumentCipher.newDek()

    private fun enc(data: ByteArray) = ByteArrayOutputStream().also {
        DocumentCipher.encrypt(ByteArrayInputStream(data), it, dek)
    }.toByteArray()

    private fun dec(blob: ByteArray, key: ByteArray = dek) = ByteArrayOutputStream().also {
        DocumentCipher.decrypt(ByteArrayInputStream(blob), it, key)
    }.toByteArray()

    @Test fun roundTripVariousSizes() {
        val c = DocumentCipher.CHUNK_SIZE
        for (n in listOf(0, 1, c - 1, c, c + 1, 3 * c + 5)) {
            val data = Random.nextBytes(n)
            assertArrayEquals("size $n", data, dec(enc(data)))
        }
    }

    @Test fun wrongKeyFails() = expectFailure { dec(enc(Random.nextBytes(100)), DocumentCipher.newDek()) }

    @Test fun bitFlipFails() {
        val blob = enc(Random.nextBytes(1000)); blob[blob.size - 5] = (blob[blob.size - 5].toInt() xor 1).toByte()
        expectFailure { dec(blob) }
    }

    @Test fun truncationAtChunkBoundaryFails() {
        val c = DocumentCipher.CHUNK_SIZE
        val blob = enc(Random.nextBytes(2 * c + 10))
        val firstChunkEnd = 12 + 4 + (c + 16)
        expectFailure { dec(blob.copyOf(firstChunkEnd)) }
    }

    @Test fun maxSizeEnforced() {
        try {
            DocumentCipher.encrypt(ByteArrayInputStream(ByteArray(500)), ByteArrayOutputStream(), dek, 100)
            fail("expected IOException")
        } catch (e: IOException) { assertEquals("File too large", e.message) }
    }

    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("expected failure") } catch (_: GeneralSecurityException) {} catch (_: IOException) {}
    }
}
