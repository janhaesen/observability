package io.github.aeshen.observability.config.encryption

data class RsaKeyWrapped(
    val recipientPublicKeyPem: String,
) : EncryptionConfig
