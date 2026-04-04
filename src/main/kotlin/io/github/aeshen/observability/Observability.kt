package io.github.aeshen.observability

import io.github.aeshen.observability.sink.EventLevel
import java.io.Closeable

@Suppress("TooManyFunctions")
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

    // Java-friendly overloads (Kotlin default parameters are inaccessible with fewer args from Java)

    fun trace(name: EventName) = trace(name, null, ObservabilityContext.empty())

    fun trace(
        name: EventName,
        message: String,
    ) = trace(name, message, ObservabilityContext.empty())

    fun debug(name: EventName) = debug(name, null, ObservabilityContext.empty())

    fun debug(
        name: EventName,
        message: String,
    ) = debug(name, message, ObservabilityContext.empty())

    fun info(name: EventName) = info(name, null, ObservabilityContext.empty())

    fun info(
        name: EventName,
        message: String,
    ) = info(name, message, ObservabilityContext.empty())

    fun warn(name: EventName) = warn(name, null, null, ObservabilityContext.empty())

    fun warn(
        name: EventName,
        message: String,
    ) = warn(name, message, null, ObservabilityContext.empty())

    fun warn(
        name: EventName,
        message: String,
        throwable: Throwable,
    ) = warn(name, message, throwable, ObservabilityContext.empty())

    fun error(name: EventName) = error(name, null, null, ObservabilityContext.empty())

    fun error(
        name: EventName,
        message: String,
    ) = error(name, message, null, ObservabilityContext.empty())

    fun error(
        name: EventName,
        message: String,
        throwable: Throwable,
    ) = error(name, message, throwable, ObservabilityContext.empty())
}
