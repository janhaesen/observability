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
                if (throwable != null) slf4jLogger.trace(payload, throwable) else slf4jLogger.trace(payload)
            }

            EventLevel.DEBUG -> {
                if (throwable != null) slf4jLogger.debug(payload, throwable) else slf4jLogger.debug(payload)
            }

            EventLevel.INFO -> {
                if (throwable != null) slf4jLogger.info(payload, throwable) else slf4jLogger.info(payload)
            }

            EventLevel.WARN -> {
                if (throwable != null) slf4jLogger.warn(payload, throwable) else slf4jLogger.warn(payload)
            }

            EventLevel.ERROR -> {
                if (throwable != null) slf4jLogger.error(payload, throwable) else slf4jLogger.error(payload)
            }
        }
    }
}
