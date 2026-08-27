package com.unityinflow.sample.shipment

import com.unityinflow.sample.api.ConflictException
import com.unityinflow.sample.api.ErrorCode
import com.unityinflow.sample.api.ResourceNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST endpoints for shipments: create, fetch, list, and confirm.
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
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No shipment with id '$shipmentId'",
            )

        return when (shipment.status) {
            // Already in the target state: the transition has nothing left to do, so the
            // call succeeds and reports the current shipment. This is what makes a retry
            // safe, and it is deliberately not a conflict.
            ShipmentStatus.CONFIRMED -> shipment

            ShipmentStatus.CREATED ->
                repository.save(shipment.copy(status = ShipmentStatus.CONFIRMED))

            ShipmentStatus.CANCELLED -> throw ResponseStatusException(
                HttpStatus.CONFLICT,
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
