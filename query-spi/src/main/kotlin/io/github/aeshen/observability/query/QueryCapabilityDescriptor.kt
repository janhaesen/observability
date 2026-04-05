package io.github.aeshen.observability.query

/**
 * Describes the set of [QueryCapability] values that a backend implementation supports.
 *
 * Create a descriptor by listing the supported capabilities:
 * ```kotlin
 * val capabilities = QueryCapabilityDescriptor(
 *     setOf(QueryCapability.OFFSET_PAGINATION, QueryCapability.SORT)
 * )
 * ```
 *
 * Use the built-in presets for common configurations:
 * - [FULL] – all known capabilities are supported
 * - [MINIMAL] – only offset pagination is guaranteed
 *
 * @see QueryCapabilityAware
 * @see QueryCapabilityValidator
 */
data class QueryCapabilityDescriptor(
    val supported: Set<QueryCapability>,
) {
    /** Returns `true` if [capability] is in the supported set. */
    fun supports(capability: QueryCapability): Boolean = capability in supported

    companion object {
        /** Descriptor that declares support for every known [QueryCapability]. */
        val FULL: QueryCapabilityDescriptor = QueryCapabilityDescriptor(QueryCapability.entries.toSet())

        /**
         * Minimal baseline descriptor: only [QueryCapability.OFFSET_PAGINATION] is guaranteed.
         *
         * Use this as the starting point for backends that have not yet declared their full
         * capability surface.
         */
        val MINIMAL: QueryCapabilityDescriptor = QueryCapabilityDescriptor(setOf(QueryCapability.OFFSET_PAGINATION))
    }
}

/**
 * Optional mixin for [AuditSearchQueryService] implementations that wish to expose their
 * supported query capabilities.
 *
 * Implementing this interface is **opt-in** – existing implementations remain valid without it.
 * Consumers can introspect capabilities via a safe cast:
 *
 * ```kotlin
 * val caps = (queryService as? QueryCapabilityAware)?.capabilities
 *     ?: QueryCapabilityDescriptor.MINIMAL
 * QueryCapabilityValidator.validate(query, caps)
 * ```
 *
 * @see QueryCapabilityDescriptor
 * @see QueryCapabilityValidator
 */
interface QueryCapabilityAware {
    /** The capabilities supported by this backend implementation. */
    val capabilities: QueryCapabilityDescriptor
}
