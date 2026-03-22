package io.github.aeshen.observability

import io.github.aeshen.observability.codec.ObservabilityCodec
import io.github.aeshen.observability.diagnostics.ObservabilityDiagnostics
import io.github.aeshen.observability.enricher.MetadataEnricher
import io.github.aeshen.observability.processor.ObservabilityProcessor
import io.github.aeshen.observability.sink.ObservabilitySink
import java.io.Closeable
import java.io.IOException
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
            emitWhenOpen(event)
        }
    }

    private fun emitWhenOpen(event: ObservabilityEvent) {
        ensureOpen()
        deliverToSinks(prepareEvent(event))
    }

    private fun ensureOpen() {
        check(open.get()) { "Observability is closed." }
    }

    private fun prepareEvent(event: ObservabilityEvent) = encodeAndProcess(applyContextProviders(event))

    private fun encodeAndProcess(event: ObservabilityEvent) =
        codec
            .encode(event)
            .apply {
                metadata["event"] = event.name.resolvedName()
                metadata["level"] = event.level.name
                metadata["size"] = encoded.size
                metadataEnrichers.forEach { it.enrich(this) }
            }
            .let { initial ->
                processors.fold(initial) { encodedEvent, processor -> processor.process(encodedEvent) }
            }
            .apply {
                metadata["size"] = encoded.size
            }

    private fun deliverToSinks(encoded: io.github.aeshen.observability.codec.EncodedEvent) {
        for (sink in sinks) {
            deliverToSink(sink, encoded)
        }
    }

    private fun deliverToSink(
        sink: ObservabilitySink,
        encoded: io.github.aeshen.observability.codec.EncodedEvent,
    ) {
        try {
            sink.handle(encoded)
        } catch (e: IllegalArgumentException) {
            handleSinkError(sink, encoded, e)
        } catch (e: IllegalStateException) {
            handleSinkError(sink, encoded, e)
        }
    }

    private fun handleSinkError(
        sink: ObservabilitySink,
        encoded: io.github.aeshen.observability.codec.EncodedEvent,
        error: Exception,
    ) {
        diagnostics.onSinkHandleError(sink = sink, event = encoded, error = error)
        if (failOnSinkError) {
            throw error
        }
    }

    override fun close() {
        lifecycleLock.write {
            if (!open.compareAndSet(true, false)) {
                return
            }

            if (failOnSinkError) {
                closeStrictly()
            } else {
                closeBestEffort()
            }
        }
    }

    private fun closeStrictly() {
        var first: Exception? = null
        sinks.forEach { sink ->
            closeSink(sink)?.let { error ->
                if (first == null) {
                    first = error
                }
            }
        }
        first?.let { throw it }
    }

    private fun closeBestEffort() {
        sinks.forEach { sink ->
            closeSink(sink)
        }
    }

    private fun closeSink(sink: ObservabilitySink): Exception? =
        try {
            sink.close()
            null
        } catch (e: IOException) {
            diagnostics.onSinkCloseError(sink = sink, error = e)
            e
        } catch (e: IllegalArgumentException) {
            diagnostics.onSinkCloseError(sink = sink, error = e)
            e
        } catch (e: IllegalStateException) {
            diagnostics.onSinkCloseError(sink = sink, error = e)
            e
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
