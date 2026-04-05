package io.github.aeshen.observability.query

/**
 * Enumeration of query features that a backend implementation may or may not support.
 *
 * Use [QueryCapabilityDescriptor] to compose a set of supported capabilities, and
 * [QueryCapabilityValidator] to validate an [AuditSearchQuery] against those capabilities
 * before executing it.
 *
 * @see QueryCapabilityDescriptor
 * @see QueryCapabilityAware
 * @see QueryCapabilityValidator
 */
enum class QueryCapability {
    /** Full-text search via [AuditSearchQuery.text] / [AuditTextQuery]. */
    TEXT_SEARCH,

    /** Custom sort order via [AuditSearchQuery.sort] (beyond the default timestamp-DESC). */
    SORT,

    /** Grouped / nested criteria via [AuditCriterion.Group]. */
    NESTED_CRITERIA,

    /** Traditional limit/offset pagination via [AuditPagination.Offset]. */
    OFFSET_PAGINATION,

    /** Keyset / cursor-based pagination via [AuditPagination.Cursor]. */
    CURSOR_PAGINATION,

    /**
     * Field-level result projections.
     *
     * Forward-looking: the shared query model does not yet expose a projection field.
     * Backends that support projections through their own extension points can declare this
     * capability so consumers can gate on it.
     */
    PROJECTIONS,
}
