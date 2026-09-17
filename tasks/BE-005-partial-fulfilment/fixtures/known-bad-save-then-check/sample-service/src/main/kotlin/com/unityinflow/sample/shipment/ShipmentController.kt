package com.unityinflow.sample.shipment

import com.unityinflow.sample.api.ConflictException
import com.unityinflow.sample.api.ErrorCode
import com.unityinflow.sample.api.FieldViolation
import com.unityinflow.sample.api.PageQuery
import com.unityinflow.sample.api.ResourceNotFoundException
import com.unityinflow.sample.api.ValidationException
import com.unityinflow.sample.api.page
import com.unityinflow.sample.order.InMemoryOrderRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/shipments")
class ShipmentController(
    private val repository: InMemoryShipmentRepository,
    private val orders: InMemoryOrderRepository,
) {

    @PostMapping
    fun create(@RequestBody request: CreateShipmentRequest): ResponseEntity<Shipment> {
        if (request.quantity < 1) {
            throw ValidationException(
                "Shipment quantity must be positive",
                listOf(FieldViolation("quantity", "must be at least 1")),
            )
        }
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
                quantity = request.quantity,
            ),
        )

        // An order that does not exist is not this feature's concern — the baseline never
        // checked, and the ticket says to keep that. An order that exists has a quantity.
        orders.findById(request.orderId)?.let { order ->
            val allocated = repository.findByOrderId(order.orderId).allocatedQuantity()
            if (allocated > order.quantity) {
                throw ConflictException(
                    ErrorCode.ORDER_OVER_ALLOCATED,
                    "Order '${order.orderId}' can allocate ${order.quantity}; ${allocated} is too much",
                )
            }
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(saved)
    }

    @GetMapping("/{shipmentId}")
    fun getById(@PathVariable shipmentId: String): Shipment = find(shipmentId)

    @GetMapping
    fun list(
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) offset: Int?,
    ): ResponseEntity<List<Shipment>> =
        repository.findAll().page(PageQuery.of(limit, offset))

    @PostMapping("/{shipmentId}/confirm")
    fun confirm(@PathVariable shipmentId: String): Shipment =
        transition(shipmentId, from = ShipmentStatus.CREATED, to = ShipmentStatus.CONFIRMED)

    @PostMapping("/{shipmentId}/deliver")
    fun deliver(@PathVariable shipmentId: String): Shipment =
        transition(shipmentId, from = ShipmentStatus.CONFIRMED, to = ShipmentStatus.DELIVERED)

    @PostMapping("/{shipmentId}/cancel")
    fun cancel(@PathVariable shipmentId: String): Shipment =
        transition(shipmentId, from = ShipmentStatus.CREATED, to = ShipmentStatus.CANCELLED)

    private fun find(shipmentId: String): Shipment =
        repository.findById(shipmentId)
            ?: throw ResourceNotFoundException(
                ErrorCode.SHIPMENT_NOT_FOUND,
                "No shipment with id '$shipmentId'",
            )

    /**
     * Every transition is the same check: the shipment must be in exactly the state the
     * transition starts from. Nothing else changes — the order's fulfilment is read from
     * the shipments, so a cancel releases its quantity by being CANCELLED.
     */
    private fun transition(shipmentId: String, from: ShipmentStatus, to: ShipmentStatus): Shipment {
        val shipment = find(shipmentId)
        if (shipment.status != from) {
            throw ConflictException(
                ErrorCode.SHIPMENT_STATE_CONFLICT,
                "Shipment '$shipmentId' is ${shipment.status} and cannot move to $to",
            )
        }
        return repository.save(shipment.copy(status = to))
    }
}
