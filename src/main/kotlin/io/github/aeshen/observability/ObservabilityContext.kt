package io.github.aeshen.observability

import io.github.aeshen.observability.key.TypedKey

/**
 * Type-safe context container backed by a map of TypedKey<\*> -> Any.
 */
class ObservabilityContext private constructor(
    private val entriesByName: Map<String, Pair<TypedKey<*>, Any>>,
) {
    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: TypedKey<T>): T? = entriesByName[key.keyName]?.second as T?

    fun asMap(): Map<TypedKey<*>, Any> = entriesByName.values.associate { it.first to it.second }

    companion object {
        @JvmStatic
        fun builder(): Builder = Builder()

        @JvmStatic
        fun empty(): ObservabilityContext = ObservabilityContext(emptyMap())
    }

    class Builder internal constructor() {
        private val mapByName: MutableMap<String, Pair<TypedKey<*>, Any>> = linkedMapOf()

        fun <T> put(
            key: TypedKey<T>,
            value: T,
        ): Builder {
            mapByName[key.keyName] = key to (value as Any)
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
            other.entriesByName.forEach { (name, pair) -> mapByName[name] = pair }
            return this
        }

        fun build(): ObservabilityContext = ObservabilityContext(mapByName.toMap())
    }
}
