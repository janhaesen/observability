package io.github.aeshen.observability.util

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object CryptoUtils {
    private val secureRandom = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { secureRandom.nextBytes(it) }

    fun loadPublicKeyFromPem(pem: String): PublicKey {
        val cleaned =
            pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\s".toRegex(), "")
        val decoded = Base64.getDecoder().decode(cleaned)
        val spec = X509EncodedKeySpec(decoded)
        return KeyFactory.getInstance("RSA").generatePublic(spec)
    }

    fun loadPrivateKeyFromPem(pem: String): PrivateKey {
        val cleaned =
            pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")
        val decoded = Base64.getDecoder().decode(cleaned)
        val spec = PKCS8EncodedKeySpec(decoded)
        return KeyFactory.getInstance("RSA").generatePrivate(spec)
    }
}
