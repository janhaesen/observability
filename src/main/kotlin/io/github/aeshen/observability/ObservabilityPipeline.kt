package io.github.aeshen.observability

import io.github.aeshen.observability.codec.ObservabilityCodec
import io.github.aeshen.observability.processor.ObservabilityProcessor
import io.github.aeshen.observability.sink.ObservabilitySink
import io.github.aeshen.observability.transport.MetadataEnricher
import java.io.Closeable

/**
 * Small facade used by applications.
 *
 * Framework note: [ObservabilityPipeline] is [Closeable] so sink resources can be released deterministically.
 * By default sink errors are best-effort (swallowed). Enable strict mode via failOnSinkError.
 */
@Suppress("TooGenericExceptionCaught")
internal class ObservabilityPipeline internal constructor(
    private val codec: ObservabilityCodec,
    private val metadataEnrichers: List<MetadataEnricher> = emptyList(),
    private val processors: List<ObservabilityProcessor>,
    private val sinks: List<ObservabilitySink>,
    private val failOnSinkError: Boolean = false,
) : Observability {
    override fun emit(event: ObservabilityEvent) {
        // Encode
        val encoded = codec.encode(event)

        metadataEnrichers.forEach { it.enrich(encoded) }

        // Apply processors, e.g. first encrypt
        processors.forEach { processor -> processor.process(encoded) }

        // Fan-out to sinks
        for (sink in sinks) {
            try {
                sink.handle(encoded)
            } catch (t: Throwable) {
                if (failOnSinkError) {
                    throw t
                } else {
                    System.err.println("Logging sink error: ${t.message}")
                }
            }
        }
    }

    override fun close() {
        if (failOnSinkError) {
            var first: Throwable? = null
            sinks.forEach {
                try {
                    it.close()
                } catch (t: Throwable) {
                    if (first == null) {
                        first = t
                    }
                }
            }
            if (first != null) {
                throw first
            }
        } else {
            sinks.forEach {
                try {
                    it.close()
                } catch (
                    t: Throwable,
                ) {
                    System.err.println("Logging sink close error: ${t.message}")
                }
            }
        }
    }
}
