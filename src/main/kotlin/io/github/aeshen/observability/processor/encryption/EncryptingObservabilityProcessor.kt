package io.github.aeshen.observability.processor.encryption

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.processor.ObservabilityProcessor
import io.github.aeshen.observability.sink.ObservabilitySink
import java.security.PublicKey
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEY_SIZE = 256
private const val RANDOM_BYTE_COUNT = 12
private const val TAG_LENGTH = 128

/**
 * Framework implementation detail.
 *
 * Wraps another [ObservabilitySink] and encrypts the payload before delegating.
 * Uses AES/GCM with a fresh data key per record; optionally wraps the data key with RSA.
 *
 * Output is a UTF-8 JSON line envelope so downstream sinks can stay byte-oriented.
 */
internal class EncryptingObservabilityProcessor private constructor(
    private val dataKeyProvider: () -> SecretKey,
    private val keyWrapper: ((SecretKey) -> ByteArray)?,
    private val secureRandomBytes: (Int) -> ByteArray,
) : ObservabilityProcessor {
    companion object {
        fun aesGcm(
            key: SecretKey,
            secureRandomBytes: (Int) -> ByteArray,
        ): EncryptingObservabilityProcessor =
            EncryptingObservabilityProcessor(
                dataKeyProvider = { key },
                keyWrapper = null,
                secureRandomBytes = secureRandomBytes,
            )

        fun aesGcmWithRsaWrappedDataKey(
            recipientPublicKey: PublicKey,
            secureRandomBytes: (Int) -> ByteArray,
        ): EncryptingObservabilityProcessor {
            val wrap: (SecretKey) -> ByteArray = { sekret ->
                val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
                rsa.init(Cipher.ENCRYPT_MODE, recipientPublicKey)
                rsa.doFinal(sekret.encoded)
            }
            return EncryptingObservabilityProcessor(
                dataKeyProvider = { generateAes256Key() },
                keyWrapper = wrap,
                secureRandomBytes = secureRandomBytes,
            )
        }

        private fun generateAes256Key(): SecretKey {
            val kg = KeyGenerator.getInstance("AES")
            kg.init(KEY_SIZE)
            return kg.generateKey()
        }
    }

    override fun process(event: EncodedEvent): EncodedEvent {
        val key = dataKeyProvider()
        val iv = secureRandomBytes(RANDOM_BYTE_COUNT) // GCM recommended 96-bit IV
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))

        val ciphertext = cipher.doFinal(event.encoded)

        val b64 = Base64.getEncoder()
        val ivB64 = b64.encodeToString(iv)
        val ctB64 = b64.encodeToString(ciphertext)

        val wrappedKeyB64 =
            keyWrapper
                ?.invoke(key)
                ?.let { b64.encodeToString(it) }

        // Keep it simple: JSONL envelope, no external deps.
        val encryptedEvent =
            buildString {
                append("{")
                append("\"alg\":\"A256GCM\",")
                append("\"iv\":\"").append(ivB64).append("\",")
                if (wrappedKeyB64 != null) {
                    append("\"wrappedKeyAlg\":\"RSA-OAEP-256\",")
                    append("\"wrappedKey\":\"").append(wrappedKeyB64).append("\",")
                }
                append("\"ciphertext\":\"").append(ctB64).append("\"")
                append("}\n")
            }.toByteArray(Charsets.UTF_8)
        return event.copy(encoded = encryptedEvent)
    }
}
