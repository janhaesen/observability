package io.github.aeshen.observability.key

/**
 * A marker for typed log keys. Implementations (enums) bind a concrete Kotlin type `T`.
 */
interface TypedKey<T> {
    val keyName: String
}
