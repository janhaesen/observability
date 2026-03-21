package io.github.aeshen.observability.sink.decorator

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.sink.ObservabilitySink

class RetryingObservabilitySink(
    private val delegate: ObservabilitySink,
    private val maxAttempts: Int = 3,
    private val backoff: BackoffStrategy = BackoffStrategy.exponential(),
    private val sleep: (Long) -> Unit = { millis -> Thread.sleep(millis) },
) : ObservabilitySink {
    init {
        require(maxAttempts > 0) { "maxAttempts must be greater than 0." }
    }

    override fun handle(event: EncodedEvent) {
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                delegate.handle(event)
                return
            } catch (t: Exception) {
                lastError = t
                if (attempt == maxAttempts) {
                    throw t
                }
                sleep(backoff.nextDelayMillis(attempt))
            }
        }

        throw IllegalStateException("Retry loop exited unexpectedly.", lastError)
    }

    override fun close() {
        delegate.close()
    }
}
