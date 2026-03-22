package io.github.aeshen.observability

import io.github.aeshen.observability.codec.ObservabilityCodec
import io.github.aeshen.observability.diagnostics.ObservabilityDiagnostics
import io.github.aeshen.observability.enricher.MetadataEnricher
import io.github.aeshen.observability.processor.ObservabilityProcessor
import io.github.aeshen.observability.sink.ObservabilitySink
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Small facade used by applications.
 *
 * Framework note: [ObservabilityPipeline] is [Closeable] so sink resources can be released deterministically.
 * By default sink errors are best-effort (swallowed). Enable strict mode via failOnSinkError.
 */
internal class ObservabilityPipeline internal constructor(
    private val codec: ObservabilityCodec,
    private val contextProviders: List<ContextProvider> = emptyList(),
    private val metadataEnrichers: List<MetadataEnricher> = emptyList(),
    private val processors: List<ObservabilityProcessor>,
    private val sinks: List<ObservabilitySink>,
    private val failOnSinkError: Boolean = false,
    private val diagnostics: ObservabilityDiagnostics = ObservabilityDiagnostics.NOOP,
) : Observability {
    private val open = AtomicBoolean(true)
    private val lifecycleLock = ReentrantReadWriteLock()

    override fun emit(event: ObservabilityEvent) {
        lifecycleLock.read {
            check(open.get()) { "Observability is closed." }

            val enrichedEvent = applyContextProviders(event)

            // Encode
            var encoded = codec.encode(enrichedEvent)
            encoded.metadata["event"] = enrichedEvent.name.resolvedName()
            encoded.metadata["level"] = enrichedEvent.level.name
            encoded.metadata["size"] = encoded.encoded.size

            metadataEnrichers.forEach { it.enrich(encoded) }

            // Apply processors, e.g. first encrypt
            processors.forEach { processor -> encoded = processor.process(encoded) }
            encoded.metadata["size"] = encoded.encoded.size

            // Fan-out to sinks
            for (sink in sinks) {
                try {
                    sink.handle(encoded)
                } catch (t: Exception) {
                    diagnostics.onSinkHandleError(sink = sink, event = encoded, error = t)
                    if (failOnSinkError) {
                        throw t
                    }
                }
            }
        }
    }

    override fun close() {
        lifecycleLock.write {
            if (!open.compareAndSet(true, false)) {
                return
            }

            if (failOnSinkError) {
                var first: Exception? = null
                sinks.forEach {
                    try {
                        it.close()
                    } catch (t: Exception) {
                        diagnostics.onSinkCloseError(sink = it, error = t)
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
                        t: Exception,
                    ) {
                        diagnostics.onSinkCloseError(sink = it, error = t)
                    }
                }
            }
        }
    }

    private fun applyContextProviders(event: ObservabilityEvent): ObservabilityEvent {
        if (contextProviders.isEmpty()) {
            return event
        }

        val merged = ObservabilityContext.builder()
        contextProviders.forEach { provider ->
            merged.putAll(provider.provide())
        }
        merged.putAll(event.context)

        return ObservabilityEvent(
            name = event.name,
            level = event.level,
            timestamp = event.timestamp,
            payload = event.payload,
            message = event.message,
            context = merged.build(),
            error = event.error,
        )
    }
}
