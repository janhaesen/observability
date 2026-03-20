package io.github.aeshen.observability

/**
 * To ensure cardinality doesn't explode for metrics cluster (logs, opentelemetry, elasticsearch, etc.)
 *
 * <pre>
 * enum class EventNameExample(
 *     override val eventName: String? = null,
 * ) : EventName {
 *     HTTP("http"),
 *     NO_NAME,
 * }
 * </pre>
 */
interface EventName {
    val name: String
    val eventName: String?
}
