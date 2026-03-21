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
        val msg =
            event.original.name
                .resolvedName()
                .trimEnd('\n')

        when (event.original.level) {
            EventLevel.TRACE -> slf4jLogger.trace(msg)
            EventLevel.DEBUG -> slf4jLogger.debug(msg)
            EventLevel.INFO -> slf4jLogger.info(msg)
            EventLevel.WARN -> slf4jLogger.warn(msg)
            EventLevel.ERROR -> slf4jLogger.error(msg)
        }
    }
}
