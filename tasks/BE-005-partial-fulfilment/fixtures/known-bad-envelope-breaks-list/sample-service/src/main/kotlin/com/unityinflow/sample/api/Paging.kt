package com.unityinflow.sample.api

import org.springframework.http.ResponseEntity

/**
 * The page contract shared by every list endpoint: a page envelope with the items and
 * the total, so a client never has to read a header.
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

data class PageResponse<T>(
    val items: List<T>,
    val total: Int,
    val limit: Int,
    val offset: Int,
)

/** One page of this list, as an envelope, with the total repeated in the header. */
fun <T> List<T>.page(query: PageQuery): ResponseEntity<PageResponse<T>> =
    ResponseEntity.ok()
        .header(TOTAL_COUNT_HEADER, size.toString())
        .body(PageResponse(drop(query.offset).take(query.limit), size, query.limit, query.offset))
