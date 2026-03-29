package io.github.aeshen.observability.context.provider

import io.github.aeshen.observability.ContextProvider
import io.github.aeshen.observability.ObservabilityContext
import io.github.aeshen.observability.key.TypedKey
import org.slf4j.MDC

/**
 * A [ContextProvider] that reads all entries from the SLF4J MDC at the time of event emission
 * and attaches them to the event context.
 *
 * ### Key naming
 * MDC keys are prefixed with [prefix] and a dot separator: `"<prefix>.<mdcKey>"`.
 * Pass an empty string to attach MDC values without any namespace.
 *
 * ### Availability
 * `slf4j-api` must be on the runtime classpath. If the MDC copy returns `null` or is empty,
 * an empty context is returned silently.
 *
 * ### Merge precedence
 * Context providers are applied before event-level context. If an event explicitly sets a key that
 * also exists in the MDC, the **event-level value wins**.
 *
 * ### Example setup
 * ```kotlin
 * val observability = ObservabilityFactory.create(
 *     ObservabilityFactory.Config(
 *         sinks = listOf(/* ... */),
 *         contextProviders = listOf(MdcContextProvider()),
 *     )
 * )
 * ```
 *
 * With the provider registered, any values stored in the MDC at emit time are automatically
 * attached to every event:
 * ```kotlin
 * MDC.put("requestId", "abc-123")
 * observability.info(MyEvent.PAYMENT_PROCESSED)
 * // → event context contains "mdc.requestId" = "abc-123"
 * ```
 *
 * @param prefix Namespace prefix prepended to every MDC key (default `"mdc"`).
 */
class MdcContextProvider(
    private val prefix: String = "mdc",
) : ContextProvider {
    override fun provide(): ObservabilityContext {
        val mdc = MDC.getCopyOfContextMap() ?: return ObservabilityContext.empty()
        if (mdc.isEmpty()) return ObservabilityContext.empty()

        val normalizedPrefix = prefix.trim().trimEnd('.')
        val builder = ObservabilityContext.builder()
        mdc.forEach { (mdcKey, value) ->
            val fullKey = if (normalizedPrefix.isBlank()) mdcKey else "$normalizedPrefix.$mdcKey"
            builder.put(DynamicStringKey(fullKey), value)
        }
        return builder.build()
    }

    /** A runtime-created [TypedKey] for a single MDC entry. */
    private data class DynamicStringKey(
        override val keyName: String,
    ) : TypedKey<String>
}
