package io.github.aeshen.observability

import io.github.aeshen.observability.ObservabilityEvent.EventBuilder
import io.github.aeshen.observability.key.TypedKey
import io.github.aeshen.observability.sink.EventLevel
import kotlin.time.Clock
import kotlin.time.Instant

class ObservabilityEvent internal constructor(
    val name: EventName,
    val level: EventLevel,
    val timestamp: Instant = Clock.System.now(),
    val payload: ByteArray? = null,
    val message: String? = null,
    val context: ObservabilityContext,
    val error: Throwable? = null,
) {
    class EventBuilder(
        private val name: EventName,
    ) {
        private var level: EventLevel = EventLevel.INFO

        private var payload: ByteArray? = null
        private var message: String? = null

        private val context: ObservabilityContext.Builder = ObservabilityContext.builder()
        private var error: Throwable? = null

        fun level(level: EventLevel) = apply { this.level = level }

        fun <T> context(
            key: TypedKey<T>,
            value: T,
        ) = apply { context.put(key, value) }

        fun context(other: ObservabilityContext) = apply { context.putAll(other) }

        fun payload(value: ByteArray) = apply { payload = value }

        fun message(msg: String) = apply { this.message = msg }

        fun error(t: Throwable) = apply { this.error = t }

        fun build(): ObservabilityEvent =
            ObservabilityEvent(
                name = name,
                level = level,
                payload = payload,
                message = message,
                context = context.build(),
                error = error,
            )
    }
}

// DSL entry point
fun event(
    name: EventName,
    block: EventBuilder.() -> Unit,
): ObservabilityEvent = EventBuilder(name).apply(block).build()
