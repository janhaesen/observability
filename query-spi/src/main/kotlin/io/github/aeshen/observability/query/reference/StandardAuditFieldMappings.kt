package io.github.aeshen.observability.query.reference

import io.github.aeshen.observability.query.AuditField

sealed interface StandardAuditField {
    data object Id : StandardAuditField

    data object TimestampEpochMillis : StandardAuditField

    data object Level : StandardAuditField

    data object Event : StandardAuditField

    data object Message : StandardAuditField

    data class Context(
        val key: String,
    ) : StandardAuditField

    data class Metadata(
        val key: String,
    ) : StandardAuditField

    data class Custom(
        val key: String,
    ) : StandardAuditField
}

/**
 * Canonical parser for [AuditField] keys used by the typed query SPI.
 */
object StandardAuditFieldMappings {
    const val CONTEXT_PREFIX: String = "context."
    const val METADATA_PREFIX: String = "metadata."

    fun resolve(field: AuditField): StandardAuditField {
        val key = field.key

        return when {
            key == AuditField.ID.key -> StandardAuditField.Id
            key == AuditField.TIMESTAMP_EPOCH_MILLIS.key -> StandardAuditField.TimestampEpochMillis
            key == AuditField.LEVEL.key -> StandardAuditField.Level
            key == AuditField.EVENT.key -> StandardAuditField.Event
            key == AuditField.MESSAGE.key -> StandardAuditField.Message
            key.startsWith(CONTEXT_PREFIX) && key.length > CONTEXT_PREFIX.length ->
                StandardAuditField.Context(key.removePrefix(CONTEXT_PREFIX))

            key.startsWith(METADATA_PREFIX) && key.length > METADATA_PREFIX.length ->
                StandardAuditField.Metadata(key.removePrefix(METADATA_PREFIX))

            else -> StandardAuditField.Custom(key)
        }
    }
}

/**
 * Default field mapper that preserves canonical keys unless overridden.
 */
class StandardAuditFieldMapper(
    private val idField: String = AuditField.ID.key,
    private val timestampField: String = AuditField.TIMESTAMP_EPOCH_MILLIS.key,
    private val levelField: String = AuditField.LEVEL.key,
    private val eventField: String = AuditField.EVENT.key,
    private val messageField: String = AuditField.MESSAGE.key,
    private val contextPrefix: String = StandardAuditFieldMappings.CONTEXT_PREFIX,
    private val metadataPrefix: String = StandardAuditFieldMappings.METADATA_PREFIX,
) : AuditFieldMapper<String> {
    override fun map(field: AuditField): String =
        when (val resolved = StandardAuditFieldMappings.resolve(field)) {
            StandardAuditField.Id -> idField
            StandardAuditField.TimestampEpochMillis -> timestampField
            StandardAuditField.Level -> levelField
            StandardAuditField.Event -> eventField
            StandardAuditField.Message -> messageField
            is StandardAuditField.Context -> contextPrefix + resolved.key
            is StandardAuditField.Metadata -> metadataPrefix + resolved.key
            is StandardAuditField.Custom -> resolved.key
        }
}
