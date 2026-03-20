package io.github.aeshen.observability.key

import io.github.aeshen.observability.ObservabilityContext

/**
 * Wrapper typed key that prepends a namespace/prefix to an underlying TypedKey's keyName.
 * Example: NamespacedKey("request", StringKey.PATH).keyName == "request.path"
 */
class NamespacedKey<T>(
    prefix: String,
    base: TypedKey<T>,
) : TypedKey<T> {
    override val keyName: String =
        if (prefix.isBlank()) {
            base.keyName
        } else {
            "$prefix.${base.keyName}"
        }
}

/**
 * Convenience extension to put a namespaced (grouped) key into a LogContext.Builder.
 */
fun <T> ObservabilityContext.Builder.putNamespaced(
    prefix: String,
    key: TypedKey<T>,
    value: T,
): ObservabilityContext.Builder = this.put(NamespacedKey(prefix, key), value)
