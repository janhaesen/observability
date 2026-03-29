package io.github.aeshen.observability.sink.testing

import io.github.aeshen.observability.EventName
import io.github.aeshen.observability.ObservabilityContext
import io.github.aeshen.observability.ObservabilityEvent
import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.sink.EventLevel
import io.github.aeshen.observability.sink.ObservabilitySink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val TIMEOUT_IN_SECONDS: Long = 10

/**
 * Reusable contract tests for third-party sink implementations.
 */
abstract class ObservabilitySinkConformanceSuite {
    protected abstract fun createSubjectSink(): ObservabilitySink

    /**
     * Implementations should return the events observed by the subject sink in receive order.
     */
    protected abstract fun observedEvents(): List<EncodedEvent>

    protected fun assertForwardsHandledEventBytesAndMetadata() {
        createSubjectSink().use { sink ->
            sink.handle(sampleEvent("conformance-one", mapOf("source" to "suite")))
        }

        val observed = observedEvents()
        assertEquals(1, observed.size)
        assertEquals("conformance-one", observed.single().encoded.toString(Charsets.UTF_8))
        assertEquals("suite", observed.single().metadata["source"])
    }

    protected fun assertCloseCanBeCalledRepeatedly() {
        val sink = createSubjectSink()
        sink.close()
        sink.close()
        assertTrue(true)
    }

    protected fun assertConcurrentHandleSafety(
        threads: Int = 4,
        eventsPerThread: Int = 50,
    ) {
        val sink = createSubjectSink()
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) { t ->
            pool.submit {
                start.await()
                repeat(eventsPerThread) { i ->
                    sink.handle(sampleEvent("c-$t-$i"))
                }
                done.countDown()
            }
        }

        start.countDown()
        assertTrue(done.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS))
        sink.close()
        pool.shutdownNow()

        assertEquals(threads * eventsPerThread, observedEvents().size)
    }

    protected fun assertCloseRejectsFurtherWritesDeterministically() {
        val sink = createSubjectSink()
        sink.close()
        assertFailsWith<Throwable> {
            sink.handle(sampleEvent("after-close"))
        }
    }

    protected fun assertHandleErrorModeContract() {
        val failing =
            object : ObservabilitySink {
                private val attempts = AtomicInteger(0)

                override fun handle(event: EncodedEvent) {
                    attempts.incrementAndGet()
                    error("boom")
                }
            }

        assertFailsWith<IllegalStateException> {
            failing.handle(sampleEvent("error"))
        }
    }

    protected fun sampleEvent(
        payload: String,
        metadata: Map<String, Any?> = emptyMap(),
    ): EncodedEvent =
        EncodedEvent(
            original =
                ObservabilityEvent(
                    name = ConformanceEvent.TEST,
                    level = EventLevel.INFO,
                    context = ObservabilityContext.empty(),
                ),
            encoded = payload.toByteArray(Charsets.UTF_8),
            metadata = metadata.toMutableMap(),
        )

    private enum class ConformanceEvent(
        override val eventName: String? = null,
    ) : EventName {
        TEST("sink.conformance.test"),
    }
}
