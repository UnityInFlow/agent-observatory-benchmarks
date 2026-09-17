package com.unityinflow.sample.api

import org.springframework.http.ResponseEntity

/**
 * The page contract shared by every list endpoint.
 *
 * The body stays a plain array so callers that never page keep working; the total goes in
 * [TOTAL_COUNT_HEADER]. Both parameters are validated here, so the three list endpoints
 * refuse the same input the same way.
 */
data class PageQuery(val limit: Int, val offset: Int) {

    companion object {
        const val MAX_LIMIT = 100

        fun of(limit: Int?, offset: Int?): PageQuery {
            val resolvedLimit = limit ?: MAX_LIMIT
            val resolvedOffset = offset ?: 0
            val violations = buildList {
                if (resolvedLimit !in 1..MAX_LIMIT) {
                    add(FieldViolation("limit", "must be between 1 and $MAX_LIMIT"))
                }
                if (resolvedOffset < 0) {
                    add(FieldViolation("offset", "must not be negative"))
                }
            }
            if (violations.isNotEmpty()) {
                throw ValidationException("Invalid page parameters", violations)
            }
            return PageQuery(resolvedLimit, resolvedOffset)
        }
    }
}

const val TOTAL_COUNT_HEADER = "X-Total-Count"

/** One page of this list, with the size of the whole list in the header. */
fun <T> List<T>.page(query: PageQuery): ResponseEntity<List<T>> =
    ResponseEntity.ok()
        .header(TOTAL_COUNT_HEADER, size.toString())
        .body(drop(query.offset).take(query.limit))
