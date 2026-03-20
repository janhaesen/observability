package io.github.aeshen.observability.key

/**
 * String-valued keys. Request-scoped keys use the local name (e.g. "path", "method").
 * Apply a namespace/prefix (for example "request") when putting them into a LogContext.
 */
enum class StringKey(
    override val keyName: String,
) : TypedKey<String> {
    NAME("name"),
    USER_AGENT("user_agent"),
    REQUEST_ID("id"),
    PATH("path"),
    METHOD("method"),
}

/**
 * Long / integral-valued keys.
 */
enum class LongKey(
    override val keyName: String,
) : TypedKey<Long> {
    MS("ms"),
    STATUS_CODE("status_code"),
}

/**
 * Double / fractional-valued keys.
 */
enum class DoubleKey(
    override val keyName: String,
) : TypedKey<Double> {
    BYTES("bytes"),
}

/**
 * Boolean-valued keys.
 */
enum class BooleanKey(
    override val keyName: String,
) : TypedKey<Boolean> {
    SUCCESS("success"),
}
