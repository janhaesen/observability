@file:JvmName("KeyGroups")

package io.github.aeshen.observability.key

import io.github.aeshen.observability.ObservabilityContext

fun interface KeyGroup {
    fun apply(builder: ObservabilityContext.Builder)
}

/**
 * Apply a KeyGroup to a LogContext.Builder fluently.
 */
fun ObservabilityContext.Builder.put(group: KeyGroup): ObservabilityContext.Builder {
    group.apply(this)
    return this
}

/**
 * Build a LogContext directly from a KeyGroup.
 */
fun KeyGroup.toContext(): ObservabilityContext = ObservabilityContext.builder().also { apply(it) }.build()
