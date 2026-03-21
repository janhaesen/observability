package io.github.aeshen.observability

/**
 * Supplies contextual values that should be attached to every emitted event.
 */
fun interface ContextProvider {
    fun provide(): ObservabilityContext
}
