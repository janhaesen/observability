package io.github.aeshen.observability.context.provider

import io.github.aeshen.observability.ContextProvider
import io.github.aeshen.observability.ObservabilityContext
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Thread-local backing store propagated via [ObservabilityCoroutineContext].
 *
 * This is intentionally package-private (internal). External code interacts through
 * [withObservabilityContext] and [CoroutineContextProvider].
 */
internal val observabilityContextThreadLocal = ThreadLocal<ObservabilityContext?>()

/**
 * A [ContextProvider] that reads ambient [ObservabilityContext] installed by
 * [withObservabilityContext] and propagated through the coroutine scope via
 * [ObservabilityCoroutineContext].
 *
 * ### Setup
 * Register this provider once when building your [io.github.aeshen.observability.Observability]
 * instance:
 * ```kotlin
 * val observability = ObservabilityFactory.create(
 *     ObservabilityFactory.Config(
 *         sinks = listOf(/* ... */),
 *         contextProviders = listOf(CoroutineContextProvider()),
 *     )
 * )
 * ```
 *
 * ### Usage
 * Wrap your suspending code with [withObservabilityContext] to set the ambient context:
 * ```kotlin
 * withObservabilityContext(
 *     ObservabilityContext.builder().put(StringKey.REQUEST_ID, "abc-123").build()
 * ) {
 *     observability.info(MyEvent.REQUEST_HANDLED)
 *     // → event context contains request_id = "abc-123"
 * }
 * ```
 *
 * ### Thread safety
 * The coroutine context element installs / restores the thread-local around every suspension
 * point, so the ambient context is always consistent with the executing coroutine regardless
 * of thread switches.
 *
 * ### Merge precedence
 * Context providers run before event-level context. Event-level values always override
 * provider-supplied values for the same key.
 */
class CoroutineContextProvider : ContextProvider {
    override fun provide(): ObservabilityContext = observabilityContextThreadLocal.get() ?: ObservabilityContext.empty()
}

/**
 * A [CoroutineContext] element that propagates an [ObservabilityContext] through coroutine
 * suspensions via a thread-local.
 *
 * All nested coroutines launched within a scope that holds this element inherit the same
 * observability context. Use [withObservabilityContext] as the preferred entry point.
 */
class ObservabilityCoroutineContext(
    val observabilityContext: ObservabilityContext,
) : ThreadContextElement<ObservabilityContext?> {
    override val key: CoroutineContext.Key<*> = Key

    override fun updateThreadContext(context: CoroutineContext): ObservabilityContext? {
        val previous = observabilityContextThreadLocal.get()
        observabilityContextThreadLocal.set(observabilityContext)
        return previous
    }

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: ObservabilityContext?,
    ) {
        observabilityContextThreadLocal.set(oldState)
    }

    companion object Key : CoroutineContext.Key<ObservabilityCoroutineContext>
}

/**
 * Installs [observabilityContext] as the ambient observability context for the duration of
 * [block], making it available to any [CoroutineContextProvider] registered on an
 * [io.github.aeshen.observability.Observability] instance.
 *
 * ### Nesting
 * Calls to [withObservabilityContext] may be nested. The innermost context takes effect for
 * events emitted within that scope. When the inner scope exits the outer context is restored.
 *
 * ```kotlin
 * withObservabilityContext(outerContext) {
 *     observability.info(MyEvent.OUTER)     // uses outerContext
 *     withObservabilityContext(innerContext) {
 *         observability.info(MyEvent.INNER) // uses innerContext
 *     }
 *     observability.info(MyEvent.OUTER_AGAIN) // uses outerContext again
 * }
 * ```
 *
 * ### Merge precedence
 * Context provider values are merged before event-level context. Event-level context always
 * wins if the same key appears in both (see merge semantics documentation).
 *
 * @param observabilityContext The context to propagate through the coroutine scope.
 * @param block Suspending code that benefits from the ambient context.
 */
suspend fun <T> withObservabilityContext(
    observabilityContext: ObservabilityContext,
    block: suspend () -> T,
): T = withContext(ObservabilityCoroutineContext(observabilityContext)) { block() }
