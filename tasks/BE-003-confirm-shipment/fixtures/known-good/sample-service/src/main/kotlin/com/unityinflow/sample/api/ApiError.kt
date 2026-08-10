package com.unityinflow.sample.api

/**
 * The error contract of this service.
 *
 * Every error response carries the same envelope, so clients can branch on a stable
 * `code` instead of parsing prose:
 *
 * ```json
 * { "error": { "code": "ORDER_NOT_FOUND", "message": "No order with id 'O-9'", "fields": [] } }
 * ```
 *
 * Responses are produced centrally by [GlobalExceptionHandler]; controllers throw an
 * [ApiException] rather than assembling bodies themselves.
 */
data class ApiError(val error: ApiErrorBody)

data class ApiErrorBody(
    val code: ErrorCode,
    val message: String,
    val fields: List<FieldViolation> = emptyList(),
)

/** Which input was rejected and why — populated for validation failures. */
data class FieldViolation(val field: String, val message: String)

/**
 * The closed set of machine-readable error codes. Clients switch on these, so a code is
 * part of the public API: add one deliberately, never rename one casually.
 */
enum class ErrorCode {
    ORDER_NOT_FOUND,
    ORDER_ALREADY_EXISTS,
    SHIPMENT_NOT_FOUND,
    SHIPMENT_ALREADY_EXISTS,
    SHIPMENT_NOT_CONFIRMABLE,
    VALIDATION_FAILED,
}
