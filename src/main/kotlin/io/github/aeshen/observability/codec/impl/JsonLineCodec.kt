package io.github.aeshen.observability.codec.impl

import io.github.aeshen.observability.ObservabilityEvent
import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.codec.ObservabilityCodec
import java.util.Base64

/**
 * Minimal JSONL-like encoding without external dependencies.
 * Writes: {"headers":{...},"context":{...},"payloadBase64":"..."}
 */
internal class JsonLineCodec : ObservabilityCodec {
    override fun encode(event: ObservabilityEvent): EncodedEvent {
        val ctxJson =
            event.context.asMap().entries.joinToString(
                separator = ",",
                prefix = "{",
                postfix = "}",
            ) {
                val k = it.key.keyName
                val v = it.value.toString()
                "\"${escape(k)}\":\"${escape(v)}\""
            }
        val payloadB64 = Base64.getEncoder().encodeToString(event.payload)
        val line =
            """
            {
                "context": $ctxJson,
                "payloadBase64": "$payloadB64"
            }
            """.trimIndent()
        return EncodedEvent(original = event, encoded = line.toByteArray(Charsets.UTF_8))
    }

    private fun escape(s: String): String =
        s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}
