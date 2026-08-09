package com.unityinflow.sample.api

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Turns the service's exception types into the [ApiError] envelope.
 *
 * Keeping this in one place is what makes the error contract a contract: no controller
 * builds an error body of its own, so every client sees the same shape whatever failed.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(ApiException::class)
    fun handleApiException(exception: ApiException): ResponseEntity<ApiError> =
        ResponseEntity
            .status(exception.status)
            .body(
                ApiError(
                    ApiErrorBody(
                        code = exception.code,
                        message = exception.message,
                        fields = exception.fields,
                    ),
                ),
            )
}
