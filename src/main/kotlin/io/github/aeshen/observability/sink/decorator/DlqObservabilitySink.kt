package io.github.aeshen.observability.sink.decorator

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.diagnostics.ObservabilityDiagnostics
import io.github.aeshen.observability.sink.ObservabilitySink
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decorator that routes eligible sink failures to a dead-letter sink.
 */
class DlqObservabilitySink
    @JvmOverloads
    constructor(
        private val delegate: ObservabilitySink,
        private val dlq: ObservabilitySink,
        private val diagnostics: ObservabilityDiagnostics = ObservabilityDiagnostics.NOOP,
        private val errorFilter: (Exception) -> Boolean = { true },
    ) : ObservabilitySink {
        private val open = AtomicBoolean(true)

        override fun handle(event: EncodedEvent) {
            check(open.get()) { "DlqObservabilitySink is closed." }

            try {
                delegate.handle(event)
            } catch (e: IllegalArgumentException) {
                routeToDlq(event, e)
            } catch (e: IllegalStateException) {
                routeToDlq(event, e)
            }
        }

        override fun close() {
            if (!open.compareAndSet(true, false)) {
                return
            }

            val delegateError = closeSink(delegate)
            val dlqError = closeSink(dlq)
            if (delegateError == null) {
                dlqError?.let { throw it }
                return
            }
            if (dlqError == null) {
                throw delegateError
            }

            delegateError.addSuppressed(dlqError)
            throw delegateError
        }

        private fun routeToDlq(
            event: EncodedEvent,
            delegateError: Exception,
        ) {
            if (!errorFilter(delegateError)) {
                throw delegateError
            }

            try {
                dlq.handle(event)
                diagnostics.onDlqWrite(event = event, dlq = dlq, originalError = delegateError)
            } catch (dlqError: IOException) {
                handleDlqFailure(event, delegateError, dlqError)
            } catch (dlqError: IllegalArgumentException) {
                handleDlqFailure(event, delegateError, dlqError)
            } catch (dlqError: IllegalStateException) {
                handleDlqFailure(event, delegateError, dlqError)
            }
        }

        private fun closeSink(sink: ObservabilitySink): Exception? =
            try {
                sink.close()
                null
            } catch (e: IOException) {
                e
            } catch (e: IllegalArgumentException) {
                e
            } catch (e: IllegalStateException) {
                e
            }

        private fun handleDlqFailure(
            event: EncodedEvent,
            delegateError: Exception,
            dlqError: Exception,
        ): Nothing {
            diagnostics.onSinkHandleError(sink = dlq, event = event, error = dlqError)
            throw IllegalStateException("Dead-letter routing failed after delegated delivery error.", dlqError).apply {
                addSuppressed(delegateError)
            }
        }
    }
