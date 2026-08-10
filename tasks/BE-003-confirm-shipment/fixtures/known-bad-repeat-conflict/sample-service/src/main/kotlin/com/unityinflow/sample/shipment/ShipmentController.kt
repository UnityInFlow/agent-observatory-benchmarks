package com.unityinflow.sample.shipment

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
 * Known-bad fixture: treats a repeated confirm as a conflict.
 *
 * This is the plausible wrong answer. ConflictException already exists in this service,
 * "already confirmed" reads like a conflict, and the surrounding code uses exactly this
 * shape for duplicate creation. It is competent, idiomatic, and violates the requirement
 * the ticket states in bold. The evaluator must fail it functionally (F03), not on the
 * error contract — the envelope here is correct.
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
    fun confirm(@PathVariable shipmentId: String): Shipment {
        val shipment = repository.findById(shipmentId)
            ?: throw ResourceNotFoundException(
                ErrorCode.SHIPMENT_NOT_FOUND,
                "No shipment with id '$shipmentId'",
            )

        return when (shipment.status) {
            ShipmentStatus.CONFIRMED -> throw ConflictException(
                ErrorCode.SHIPMENT_NOT_CONFIRMABLE,
                "Shipment '$shipmentId' is already confirmed",
            )

            ShipmentStatus.CREATED ->
                repository.save(shipment.copy(status = ShipmentStatus.CONFIRMED))

            ShipmentStatus.CANCELLED -> throw ConflictException(
                ErrorCode.SHIPMENT_NOT_CONFIRMABLE,
                "Shipment '$shipmentId' is ${shipment.status} and cannot be confirmed",
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
