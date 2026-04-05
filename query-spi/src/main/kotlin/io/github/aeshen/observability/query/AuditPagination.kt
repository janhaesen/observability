package io.github.aeshen.observability.query

/**
 * Pagination strategy for [AuditSearchQuery].
 *
 * Use [Offset] for traditional limit/offset pagination.
 * Use [Cursor] for cursor-based (keyset) pagination when the backend supports it.
 *
 * Backends that return a cursor will populate [io.github.aeshen.observability.query.AuditQueryResult.nextCursor].
 * Pass that value as [Cursor.after] in the next request to retrieve the following page.
 *
 * **Note on stable sort:** cursor pagination requires a deterministic sort order. Ensure the
 * [AuditSearchQuery.sort] list includes at least one tie-breaking field (e.g. [AuditField.ID]
 * or [AuditField.TIMESTAMP_EPOCH_MILLIS]) to guarantee consistent page boundaries across requests.
 */
sealed interface AuditPagination {
    /** Maximum number of records to return per page. Must be greater than 0. */
    val limit: Int

    /**
     * Traditional offset-based pagination.
     *
     * @param limit Maximum records per page. Defaults to 100.
     * @param offset Number of records to skip. Defaults to 0.
     */
    data class Offset
        @JvmOverloads
        constructor(
            override val limit: Int = 100,
            val offset: Int = 0,
        ) : AuditPagination {
            init {
                require(limit > 0) { "limit must be greater than 0." }
                require(offset >= 0) { "offset must be greater than or equal to 0." }
            }
        }

    /**
     * Cursor-based (keyset) pagination.
     *
     * The [after] token is an opaque, backend-specific continuation marker returned in
     * [io.github.aeshen.observability.query.AuditQueryResult.nextCursor].
     * Pass it unchanged in the next request.
     *
     * @param after Opaque continuation token from the previous page's [AuditQueryResult.nextCursor].
     * @param limit Maximum records per page. Defaults to 100.
     */
    data class Cursor
        @JvmOverloads
        constructor(
            val after: String,
            override val limit: Int = 100,
        ) : AuditPagination {
            init {
                require(limit > 0) { "limit must be greater than 0." }
                require(after.isNotBlank()) { "after cursor must not be blank." }
            }
        }
}
