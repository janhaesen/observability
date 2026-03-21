package io.github.aeshen.observability.codec.impl

import io.github.aeshen.observability.ObservabilityEvent
import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.codec.ObservabilityCodec
import java.util.Base64

/**
 * Minimal JSONL-like encoding without external dependencies.
 * Writes one JSON object per line with core event fields.
 */
class JsonLineCodec : ObservabilityCodec {
    override fun encode(event: ObservabilityEvent): EncodedEvent {
        val ctxJson =
            event.context.asMap().entries.joinToString(
                separator = ",",
                prefix = "{",
                postfix = "}",
            ) {
                val k = it.key.keyName
                val v = jsonValue(it.value)
                "\"${escape(k)}\":$v"
            }

        val messageJson = event.message?.let { "\"${escape(it)}\"" } ?: "null"
        val errorJson =
            event.error?.let { throwable ->
                val type = escape(throwable.javaClass.name)
                val message = throwable.message?.let { "\"${escape(it)}\"" } ?: "null"
                val stacktrace = escape(throwable.stackTraceToString())
                "\"error\":{\"type\":\"$type\",\"message\":$message,\"stacktrace\":\"$stacktrace\"},"
            } ?: ""

        val payloadPresent = event.payload != null
        val payloadB64 = Base64.getEncoder().encodeToString(event.payload ?: byteArrayOf())
        val line =
            buildString {
                append('{')
                append("\"name\":\"").append(escape(event.name.resolvedName())).append("\",")
                append("\"level\":\"").append(event.level.name).append("\",")
                append("\"timestamp\":\"").append(event.timestamp.toString()).append("\",")
                append("\"message\":").append(messageJson).append(',')
                append(errorJson)
                append("\"context\":").append(ctxJson).append(',')
                append("\"payloadPresent\":").append(payloadPresent).append(',')
                append("\"payloadBase64\":\"").append(payloadB64).append("\"")
                append("}\n")
            }

        return EncodedEvent(original = event, encoded = line.toByteArray(Charsets.UTF_8))
    }

    private fun escape(s: String): String =
        s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    private fun jsonValue(value: Any?): String =
        when (value) {
            null -> "null"
            is Boolean -> value.toString()
            is Byte,
            is Short,
            is Int,
            is Long,
            is Float,
            is Double,
            -> numberJson(value)
            else -> "\"${escape(value.toString())}\""
        }

    private fun numberJson(number: Any): String {
        val asDouble = (number as Number).toDouble()
        return if (asDouble.isFinite()) {
            number.toString()
        } else {
            "\"${escape(number.toString())}\""
        }
    }
}
