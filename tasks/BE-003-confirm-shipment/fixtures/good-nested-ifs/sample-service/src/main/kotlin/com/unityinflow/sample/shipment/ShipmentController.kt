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
 * QUALITY VARIANT of the BE-003 reference solution — maintainability only.
 *
 * Behaviour is byte-identical to known-good: same exceptions, same error codes, same
 * messages, same status codes. Every gate passes. The architecture convention is respected
 * — refusals still throw [ApiException] subclasses and are rendered centrally.
 *
 * What differs is how the decision is expressed. known-good uses an exhaustive
 * `when (shipment.status)`, so the compiler enforces that every status is accounted for and
 * a reader sees all three transitions in one place. This variant uses a chain of `if` /
 * `else if` on the same values: adding a fourth ShipmentStatus would compile silently and
 * fall through to the final else, and the set of legal transitions is no longer visible as
 * a set.
 *
 * The comment explaining why a repeat is a success rather than a conflict — the single
 * non-obvious decision in the ticket — is also gone. A reader must infer it.
 *
 * Only `confirm` differs from known-good, so any scoring difference has one candidate cause.
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

        if (shipment.status == ShipmentStatus.CONFIRMED) {
            return shipment
        } else if (shipment.status == ShipmentStatus.CREATED) {
            return repository.save(shipment.copy(status = ShipmentStatus.CONFIRMED))
        } else {
            throw ConflictException(
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
