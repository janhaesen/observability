package io.github.aeshen.observability.sink.impl

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.sink.EventLevel
import io.github.aeshen.observability.sink.ObservabilitySink
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

/**
 * Optional bridge into SLF4J.
 */
internal class Slf4JObservabilitySink(
    clazz: KClass<*>,
) : ObservabilitySink {
    private val slf4jLogger = LoggerFactory.getLogger(clazz.java)

    override fun handle(event: EncodedEvent) {
        val payload = event.encoded.toString(Charsets.UTF_8).trimEnd('\n')
        val throwable = event.original.error

        when (event.original.level) {
            EventLevel.TRACE -> {
                logWithOptionalThrowable(payload, throwable, slf4jLogger::trace, slf4jLogger::trace)
            }

            EventLevel.DEBUG -> {
                logWithOptionalThrowable(payload, throwable, slf4jLogger::debug, slf4jLogger::debug)
            }

            EventLevel.INFO -> {
                logWithOptionalThrowable(payload, throwable, slf4jLogger::info, slf4jLogger::info)
            }

            EventLevel.WARN -> {
                logWithOptionalThrowable(payload, throwable, slf4jLogger::warn, slf4jLogger::warn)
            }

            EventLevel.ERROR -> {
                logWithOptionalThrowable(payload, throwable, slf4jLogger::error, slf4jLogger::error)
            }
        }
    }

    private fun logWithOptionalThrowable(
        payload: String,
        throwable: Throwable?,
        logMessage: (String) -> Unit,
        logThrowable: (String, Throwable) -> Unit,
    ) {
        if (throwable != null) {
            logThrowable(payload, throwable)
        } else {
            logMessage(payload)
        }
    }
}
