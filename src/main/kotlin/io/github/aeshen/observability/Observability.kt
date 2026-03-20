package io.github.aeshen.observability

import io.github.aeshen.observability.sink.EventLevel
import java.io.Closeable

interface Observability : Closeable {
    fun emit(event: ObservabilityEvent)

    fun trace(
        name: EventName,
        message: String? = null,
        context: ObservabilityContext = ObservabilityContext.empty(),
    ) = emit(
        ObservabilityEvent(
            level = EventLevel.TRACE,
            name = name,
            message = message,
            context = context,
        ),
    )

    fun debug(
        name: EventName,
        message: String? = null,
        context: ObservabilityContext = ObservabilityContext.empty(),
    ) = emit(
        ObservabilityEvent(
            level = EventLevel.DEBUG,
            name = name,
            message = message,
            context = context,
        ),
    )

    fun info(
        name: EventName,
        message: String? = null,
        context: ObservabilityContext = ObservabilityContext.empty(),
    ) = emit(
        ObservabilityEvent(
            level = EventLevel.INFO,
            name = name,
            message = message,
            context = context,
        ),
    )

    fun warn(
        name: EventName,
        message: String? = null,
        throwable: Throwable? = null,
        context: ObservabilityContext = ObservabilityContext.empty(),
    ) = emit(
        ObservabilityEvent(
            level = EventLevel.WARN,
            name = name,
            message = message,
            error = throwable,
            context = context,
        ),
    )

    fun error(
        name: EventName,
        message: String? = null,
        throwable: Throwable? = null,
        context: ObservabilityContext = ObservabilityContext.empty(),
    ) = emit(
        ObservabilityEvent(
            level = EventLevel.ERROR,
            name = name,
            message = message,
            error = throwable,
            context = context,
        ),
    )
}
