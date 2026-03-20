package io.github.aeshen.observability.sink.impl

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.sink.ObservabilitySink
import java.nio.charset.Charset

private const val MAX_PREVIEW_CHARS = 200
private const val MAX_HEX_BYTES = 64

internal class ConsoleObservabilitySink : ObservabilitySink {
    private val charset: Charset = Charsets.UTF_8
    private val previewMaxChars = MAX_PREVIEW_CHARS
    private val previewMaxBytesHex = MAX_HEX_BYTES

    override fun handle(event: EncodedEvent) {
        val eventName = event.metadata["event"] ?: "-"
        val extrasStr =
            if (event.metadata.isEmpty()) {
                "-"
            } else {
                event.metadata.entries
                    .joinToString(",") {
                        "${it.key}=${it.value}"
                    }
            }
        val preview = safePreview(event.encoded)
        println(
            "LogSink: event=$eventName size=${event.metadata["size"]} extras=$extrasStr " +
                "payloadPreview=$preview",
        )
    }

    private fun safePreview(bytes: ByteArray): String {
        // Try decode as UTF-8 text and produce a compact single-line preview.
        try {
            val s = bytes.toString(charset).trim()
            if (s.isNotEmpty()) {
                // Compact whitespace/newlines to a single space for readability.
                val compact = s.replace(Regex("\\s+"), " ")
                return if (compact.length <= previewMaxChars) {
                    compact
                } else {
                    compact.substring(0, previewMaxChars) + "…"
                }
            }
        } catch (_: Throwable) {
            // fallthrough to hex
        }

        // Fallback: show hex for binary payloads (truncated).
        val hex = bytes.take(previewMaxBytesHex).joinToString("") { "%02x".format(it) }
        return if (bytes.size > previewMaxBytesHex) {
            "hex:$hex…"
        } else {
            "hex:$hex"
        }
    }
}
