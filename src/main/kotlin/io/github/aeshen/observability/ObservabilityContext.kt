package io.github.aeshen.observability

import io.github.aeshen.observability.key.TypedKey

/**
 * Type-safe context container backed by a map of TypedKey<\*> -> Any.
 */
class ObservabilityContext private constructor(
    private val entries: Map<TypedKey<*>, Any>,
) {
    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: TypedKey<T>): T? = entries[key] as T?

    fun asMap(): Map<TypedKey<*>, Any> = entries

    companion object {
        fun builder(): Builder = Builder()

        fun empty(): ObservabilityContext = ObservabilityContext(emptyMap())
    }

    class Builder internal constructor() {
        private val map: MutableMap<TypedKey<*>, Any> = linkedMapOf()

        fun <T> put(
            key: TypedKey<T>,
            value: T,
        ): Builder {
            map[key] = value as Any
            return this
        }

        fun <T> putIfNotNull(
            key: TypedKey<T>,
            value: T?,
        ): Builder {
            if (value != null) {
                put(key, value)
            }

            return this
        }

        fun putAll(other: ObservabilityContext): Builder {
            other.asMap().forEach { (k, v) -> map[k] = v }
            return this
        }

        fun build(): ObservabilityContext = ObservabilityContext(map.toMap())
    }
}
