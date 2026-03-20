package io.github.aeshen.observability.config.encryption

import javax.crypto.SecretKey

data class AesGcm(
    val aesKey: SecretKey,
) : EncryptionConfig
