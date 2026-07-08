package io.github.aeshen.observability.spring

import io.github.aeshen.observability.config.encryption.AesGcm
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ObservabilityPropertiesTest {
    @Test
    fun aesGcmAcceptsValidHexKeys() {
        val properties =
            EncryptionProperties().apply {
                type = "aes-gcm"
                aesKeyHex = "00112233445566778899aabbccddeeff"
            }

        assertIs<AesGcm>(properties.toEncryptionConfig())
    }

    @Test
    fun aesGcmRejectsOddLengthHexKeys() {
        val properties =
            EncryptionProperties().apply {
                type = "aes-gcm"
                aesKeyHex = "abc"
            }

        val error = assertFailsWith<IllegalArgumentException> { properties.toEncryptionConfig() }

        assertTrue(error.message.orEmpty().contains("even number of characters"))
    }

    @Test
    fun aesGcmRejectsInvalidHexCharacters() {
        val properties =
            EncryptionProperties().apply {
                type = "aes-gcm"
                aesKeyHex = "00112233445566778899aabbccddeezz"
            }

        val error = assertFailsWith<IllegalArgumentException> { properties.toEncryptionConfig() }

        assertTrue(error.message.orEmpty().contains("invalid hexadecimal character"))
    }
}
