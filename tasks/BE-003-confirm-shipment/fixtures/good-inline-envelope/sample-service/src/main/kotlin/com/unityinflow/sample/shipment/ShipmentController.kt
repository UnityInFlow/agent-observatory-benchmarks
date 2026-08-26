package com.unityinflow.sample.shipment

import com.unityinflow.sample.api.ApiError
import com.unityinflow.sample.api.ApiErrorBody
import com.unityinflow.sample.api.ConflictException
import com.unityinflow.sample.api.ErrorCode
import com.unityinflow.sample.api.ResourceNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * QUALITY VARIANT of the BE-003 reference solution — architecture-consistency only.
 *
 * Behaviour is identical to known-good and every gate passes: the status codes are right,
 * and the error bodies are byte-identical to what GlobalExceptionHandler would have
 * produced, so the contract suite sees the service envelope and is satisfied.
 *
 * What differs is who built them. `confirm` assembles [ApiError] by hand and returns it on
 * a ResponseEntity instead of throwing an [ApiException] subclass and letting the handler
 * render it once, centrally. The convention declared in api/ApiExceptions.kt — "a
 * controller signals a failure by throwing one of these; the status code and the response
 * body follow from the exception, in one place" — is bypassed.
 *
 * This is the fixture that proves a quality rubric measures design rather than
 * re-measuring the evaluator: nothing deterministic can tell it apart from known-good.
 *
 * Note the two remaining throws in `create` and `getById`. They are deliberately left
 * alone — the variant differs from known-good in ONE method, so any scoring difference has
 * one candidate cause.
 */
@RestController
@RequestMapping("/shipments")
class ShipmentController(
    private val repository: InMemoryShipmentRepository,
) {

    @PostMapping
    fun create(@RequestBody request: CreateShipmentRequest): ResponseEntity<Shipment> {
        if (repository.existsById(request.shipmentId)) {
            throw ConflictException(
                ErrorCode.SHIPMENT_ALREADY_EXISTS,
                "A shipment with id '${request.shipmentId}' already exists",
            )
        }

        val saved = repository.save(
            Shipment(
                shipmentId = request.shipmentId,
                orderId = request.orderId,
                carrier = request.carrier,
                status = ShipmentStatus.CREATED,
            ),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(saved)
    }

    @PostMapping("/{shipmentId}/confirm")
    fun confirm(@PathVariable shipmentId: String): ResponseEntity<Any> {
        val shipment = repository.findById(shipmentId)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiError(
                    ApiErrorBody(
                        code = ErrorCode.SHIPMENT_NOT_FOUND,
                        message = "No shipment with id '$shipmentId'",
                    ),
                ),
            )

        return when (shipment.status) {
            // Already in the target state: the transition has nothing left to do, so the
            // call succeeds and reports the current shipment. This is what makes a retry
            // safe, and it is deliberately not a conflict.
            ShipmentStatus.CONFIRMED -> ResponseEntity.ok(shipment)

            ShipmentStatus.CREATED ->
                ResponseEntity.ok(repository.save(shipment.copy(status = ShipmentStatus.CONFIRMED)))

            ShipmentStatus.CANCELLED -> ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiError(
                    ApiErrorBody(
                        code = ErrorCode.SHIPMENT_NOT_CONFIRMABLE,
                        message = "Shipment '$shipmentId' is ${shipment.status} and cannot be confirmed",
                    ),
                ),
            )
        }
    }

    @GetMapping("/{shipmentId}")
    fun getById(@PathVariable shipmentId: String): Shipment =
        repository.findById(shipmentId)
            ?: throw ResourceNotFoundException(
                ErrorCode.SHIPMENT_NOT_FOUND,
                "No shipment with id '$shipmentId'",
            )

    @GetMapping
    fun list(): List<Shipment> = repository.findAll()
}
