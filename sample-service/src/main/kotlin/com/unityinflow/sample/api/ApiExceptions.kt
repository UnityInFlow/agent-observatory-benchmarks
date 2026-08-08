package com.unityinflow.sample.api

import org.springframework.http.HttpStatus

/**
 * Base class for every failure that is part of the API contract.
 *
 * A controller or service signals a failure by throwing one of these; the status code
 * and the response body follow from the exception, in one place.
 */
sealed class ApiException(
    val status: HttpStatus,
    val code: ErrorCode,
    override val message: String,
    val fields: List<FieldViolation> = emptyList(),
) : RuntimeException(message)

/** The addressed resource does not exist → 404. */
class ResourceNotFoundException(code: ErrorCode, message: String) :
    ApiException(HttpStatus.NOT_FOUND, code, message)

/** The request conflicts with the current state of the resource → 409. */
class ConflictException(code: ErrorCode, message: String) :
    ApiException(HttpStatus.CONFLICT, code, message)

/** The request is well-formed but violates a rule of the domain → 400. */
class ValidationException(
    message: String,
    fields: List<FieldViolation> = emptyList(),
) : ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, message, fields)
