package io.github.aeshen.observability.processor.builtin

import io.github.aeshen.observability.ObservabilityContext
import io.github.aeshen.observability.ObservabilityEvent
import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.key.TypedKey
import io.github.aeshen.observability.processor.ObservabilityProcessor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private const val DEFAULT_MASK_VALUE = "[REDACTED]"
private const val CONTEXT_PREFIX = "context."
private const val METADATA_PREFIX = "metadata."
private const val MESSAGE_PATH = "message"
private const val PAYLOAD_PATH = "payload"
private const val PAYLOAD_PRESENT_PATH = "payloadPresent"
private const val PAYLOAD_BASE64_PATH = "payloadBase64"

/**
 * Ordered rule action for [SensitiveFieldProcessor].
 *
 * The processor evaluates rules in declaration order and stops at the first match.
 */
enum class SensitiveFieldAction {
    ALLOW,
    MASK,
    REMOVE,
}

/**
 * Rule descriptor used by [SensitiveFieldProcessor].
 *
 * [fieldPattern] accepts `*` globs and is matched case-insensitively against field paths such as:
 * - `message`
 * - `payload`
 * - `context.password`
 * - `metadata.apiToken`
 */
data class SensitiveFieldRule(
    val fieldPattern: String,
    val action: SensitiveFieldAction,
    val replacement: String = DEFAULT_MASK_VALUE,
) {
    init {
        require(fieldPattern.isNotBlank()) { "fieldPattern must not be blank." }
    }

    companion object {
        fun allow(fieldPattern: String): SensitiveFieldRule =
            SensitiveFieldRule(
                fieldPattern = fieldPattern,
                action = SensitiveFieldAction.ALLOW,
            )

        fun mask(
            fieldPattern: String,
            replacement: String = DEFAULT_MASK_VALUE,
        ): SensitiveFieldRule =
            SensitiveFieldRule(
                fieldPattern = fieldPattern,
                action = SensitiveFieldAction.MASK,
                replacement = replacement,
            )

        fun remove(fieldPattern: String): SensitiveFieldRule =
            SensitiveFieldRule(
                fieldPattern = fieldPattern,
                action = SensitiveFieldAction.REMOVE,
            )
    }
}

/**
 * Ready-made rule bundles for common sensitive field names.
 */
object SensitiveFieldPresets {
    fun commonSecrets(mask: String = DEFAULT_MASK_VALUE): List<SensitiveFieldRule> =
        listOf(
            "$CONTEXT_PREFIX*password*",
            "$CONTEXT_PREFIX*passwd*",
            "$CONTEXT_PREFIX*secret*",
            "$CONTEXT_PREFIX*token*",
            "$CONTEXT_PREFIX*authorization*",
            "$CONTEXT_PREFIX*credential*",
            "$CONTEXT_PREFIX*cookie*",
            "$CONTEXT_PREFIX*session*",
            "$CONTEXT_PREFIX*api*key*",
            "$CONTEXT_PREFIX*email*",
            "$METADATA_PREFIX*password*",
            "$METADATA_PREFIX*passwd*",
            "$METADATA_PREFIX*secret*",
            "$METADATA_PREFIX*token*",
            "$METADATA_PREFIX*authorization*",
            "$METADATA_PREFIX*credential*",
            "$METADATA_PREFIX*cookie*",
            "$METADATA_PREFIX*session*",
            "$METADATA_PREFIX*api*key*",
            "$METADATA_PREFIX*email*",
        ).map { SensitiveFieldRule.mask(it, mask) }
}

/**
 * First-party processor for deterministic masking/removal of sensitive fields.
 *
 * The processor always sanitizes:
 * - `EncodedEvent.original` for `message`, `payload`, and `context.*`
 * - `EncodedEvent.metadata` for `metadata.*`
 *
 * When the encoded payload is a UTF-8 JSON object, it also sanitizes matching JSON fields.
 * This makes it work out of the box with the default JSONL codec and JSON-based custom codecs.
 */
class SensitiveFieldProcessor(
    rules: List<SensitiveFieldRule> = emptyList(),
    presets: List<SensitiveFieldRule> = emptyList(),
    private val json: Json = Json,
) : ObservabilityProcessor {
    private val compiledRules = (rules + presets).map { CompiledRule(it) }

    override fun process(event: EncodedEvent): EncodedEvent {
        if (compiledRules.isEmpty()) {
            return event
        }

        return event.copy(
            original = sanitizeOriginal(event.original),
            encoded = sanitizeEncoded(event.encoded),
            metadata = sanitizeMetadata(event.metadata),
        )
    }

    private fun sanitizeOriginal(event: ObservabilityEvent): ObservabilityEvent {
        val sanitizedContext = ObservabilityContext.builder()
        event.context.asMap().forEach { (key, value) ->
            when (val rule = resolveRule("$CONTEXT_PREFIX${key.keyName}")) {
                null,
                is CompiledRule.Allow,
                -> sanitizedContext.putUntyped(key, value)
                is CompiledRule.Mask -> sanitizedContext.putUntyped(key, rule.replacement)
                is CompiledRule.Remove -> Unit
            }
        }

        return ObservabilityEvent(
            name = event.name,
            level = event.level,
            timestamp = event.timestamp,
            payload = sanitizePayload(event.payload),
            message = sanitizeString(MESSAGE_PATH, event.message),
            context = sanitizedContext.build(),
            error = event.error,
        )
    }

    private fun sanitizePayload(payload: ByteArray?): ByteArray? {
        if (payload == null) {
            return null
        }

        return when (val rule = resolveRule(PAYLOAD_PATH)) {
            null,
            is CompiledRule.Allow,
            -> payload.copyOf()
            is CompiledRule.Mask -> rule.replacement.toByteArray(Charsets.UTF_8)
            is CompiledRule.Remove -> null
        }
    }

    private fun sanitizeString(
        path: String,
        value: String?,
    ): String? {
        if (value == null) {
            return null
        }

        return when (val rule = resolveRule(path)) {
            null,
            is CompiledRule.Allow,
            -> value
            is CompiledRule.Mask -> rule.replacement
            is CompiledRule.Remove -> null
        }
    }

    private fun sanitizeMetadata(metadata: Map<String, Any?>): MutableMap<String, Any?> {
        val sanitized = linkedMapOf<String, Any?>()
        metadata.forEach { (key, value) ->
            when (val rule = resolveRule("$METADATA_PREFIX$key")) {
                null,
                is CompiledRule.Allow,
                -> sanitized[key] = value
                is CompiledRule.Mask -> sanitized[key] = rule.replacement
                is CompiledRule.Remove -> Unit
            }
        }
        return sanitized
    }

    private fun sanitizeEncoded(encoded: ByteArray): ByteArray {
        val raw = encoded.toString(Charsets.UTF_8)
        val hasTrailingNewline = raw.endsWith("\n")
        val body = if (hasTrailingNewline) raw.dropLast(1) else raw
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return encoded
        val sanitized = sanitizeJsonObject(root)
        val rendered = json.encodeToString(JsonElement.serializer(), sanitized)
        return (rendered + if (hasTrailingNewline) "\n" else "").toByteArray(Charsets.UTF_8)
    }

    private fun sanitizeJsonObject(
        obj: JsonObject,
        parentPath: String = "",
    ): JsonObject =
        buildJsonObject {
            obj.forEach { (key, value) ->
                sanitizeJsonEntry(
                    key = key,
                    path = if (parentPath.isEmpty()) key else "$parentPath.$key",
                    value = value,
                )?.let { (entryKey, entryValue) ->
                    put(entryKey, entryValue)
                }
            }
        }

    private fun sanitizeJsonEntry(
        key: String,
        path: String,
        value: JsonElement,
    ): Pair<String, JsonElement>? {
        payloadPresenceOverride(key)?.let { return key to it }

        return when (val rule = resolveRule(path)) {
            null -> sanitizeJsonValue(path, value)?.let { key to it }
            is CompiledRule.Allow -> key to value
            is CompiledRule.Mask -> key to JsonPrimitive(rule.replacement)
            is CompiledRule.Remove -> null
        }
    }

    private fun payloadPresenceOverride(key: String): JsonPrimitive? {
        if (key != PAYLOAD_PRESENT_PATH) {
            return null
        }

        return when (resolveRule(PAYLOAD_PATH)) {
            is CompiledRule.Remove -> JsonPrimitive(false)
            is CompiledRule.Allow,
            is CompiledRule.Mask,
            null,
            -> null
        }
    }

    private fun sanitizeJsonValue(
        path: String,
        value: JsonElement,
    ): JsonElement? {
        val rule = resolveRule(path)
        return when (value) {
            is JsonObject -> sanitizeJsonObject(value, path)
            JsonNull -> JsonNull
            else ->
                when (rule) {
                    is CompiledRule.Remove -> null
                    is CompiledRule.Mask -> JsonPrimitive(rule.replacement)
                    is CompiledRule.Allow,
                    null,
                    -> value
                }
        }
    }

    private fun resolveRule(path: String): CompiledRule? {
        val normalizedPath = normalize(path)
        val alias = aliasFor(normalizedPath)
        return compiledRules.firstOrNull { rule ->
            rule.matches(normalizedPath) || rule.matches(alias)
        }
    }

    private fun aliasFor(path: String): String =
        when (path) {
            PAYLOAD_BASE64_PATH, PAYLOAD_PRESENT_PATH -> PAYLOAD_PATH
            else -> path
        }

    private fun normalize(path: String): String = path.trim().lowercase()

    @Suppress("UNCHECKED_CAST")
    private fun ObservabilityContext.Builder.putUntyped(
        key: TypedKey<*>,
        value: Any?,
    ): ObservabilityContext.Builder = this.put(key as TypedKey<Any?>, value)

    private sealed interface CompiledRule {
        fun matches(path: String): Boolean

        class Allow(
            private val matcher: Regex,
        ) : CompiledRule {
            override fun matches(path: String): Boolean = matcher.matches(path)
        }

        class Mask(
            private val matcher: Regex,
            val replacement: String,
        ) : CompiledRule {
            override fun matches(path: String): Boolean = matcher.matches(path)
        }

        class Remove(
            private val matcher: Regex,
        ) : CompiledRule {
            override fun matches(path: String): Boolean = matcher.matches(path)
        }

        companion object {
            operator fun invoke(rule: SensitiveFieldRule): CompiledRule {
                val matcher = globToRegex(rule.fieldPattern)
                return when (rule.action) {
                    SensitiveFieldAction.ALLOW -> Allow(matcher)
                    SensitiveFieldAction.MASK -> Mask(matcher, rule.replacement)
                    SensitiveFieldAction.REMOVE -> Remove(matcher)
                }
            }

            private fun globToRegex(pattern: String): Regex {
                val normalized = pattern.trim().lowercase()
                val regex =
                    buildString {
                        append('^')
                        normalized.forEach { ch ->
                            when (ch) {
                                '*' -> append(".*")
                                '.',
                                '(',
                                ')',
                                '[',
                                ']',
                                '{',
                                '}',
                                '+',
                                '?',
                                '^',
                                '$',
                                '|',
                                '\\',
                                -> append('\\').append(ch)
                                else -> append(ch)
                            }
                        }
                        append('$')
                    }
                return Regex(regex)
            }
        }
    }
}
