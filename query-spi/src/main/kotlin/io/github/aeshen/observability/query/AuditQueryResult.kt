package io.github.aeshen.observability.query

/**
 * Result of an [AuditSearchQueryService.search] call.
 *
 * @property records The records matching the query for the requested page.
 * @property total Total number of matching records across all pages (best-effort; may be -1 if
 *   the backend does not support exact counts).
 * @property nextCursor Opaque continuation token for the next page, or `null` when there are no
 *   more results or when the backend does not support cursor-based pagination. Pass this value as
 *   [AuditPagination.Cursor.after] in the subsequent request.
 */
data class AuditQueryResult
    @JvmOverloads
    constructor(
        val records: List<AuditRecord>,
        val total: Long,
        val nextCursor: String? = null,
    ) {
        init {
            require(total >= 0) { "total must be greater than or equal to 0." }
            require(total >= records.size.toLong()) { "total must be greater than or equal to records.size." }
        }
    }
