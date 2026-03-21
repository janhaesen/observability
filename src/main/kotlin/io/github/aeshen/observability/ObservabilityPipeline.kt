package io.github.aeshen.observability

import io.github.aeshen.observability.codec.ObservabilityCodec
import io.github.aeshen.observability.processor.ObservabilityProcessor
import io.github.aeshen.observability.sink.ObservabilitySink
import io.github.aeshen.observability.transport.MetadataEnricher
import java.io.Closeable
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.atomic.AtomicBoolean
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
    private val metadataEnrichers: List<MetadataEnricher> = emptyList(),
    private val processors: List<ObservabilityProcessor>,
    private val sinks: List<ObservabilitySink>,
    private val failOnSinkError: Boolean = false,
) : Observability {
    private val open = AtomicBoolean(true)
    private val lifecycleLock = ReentrantReadWriteLock()

    override fun emit(event: ObservabilityEvent) {
        lifecycleLock.read {
            check(open.get()) { "Observability is closed." }

            // Encode
            var encoded = codec.encode(event)
            encoded.metadata["event"] = event.name.resolvedName()
            encoded.metadata["level"] = event.level.name
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
                    if (failOnSinkError) {
                        throw t
                    } else {
                        System.err.println("Logging sink error: ${t.message}")
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
                        System.err.println("Logging sink close error: ${t.message}")
                    }
                }
            }
        }
    }
}
