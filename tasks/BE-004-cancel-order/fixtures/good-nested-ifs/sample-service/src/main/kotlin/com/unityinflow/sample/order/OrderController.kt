package com.unityinflow.sample.order

import com.unityinflow.sample.api.ConflictException
import com.unityinflow.sample.api.ErrorCode
import com.unityinflow.sample.api.ResourceNotFoundException
import com.unityinflow.sample.shipment.InMemoryShipmentRepository
import com.unityinflow.sample.shipment.ShipmentStatus
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/orders")
class OrderController(
    private val repository: InMemoryOrderRepository,
    private val shipments: InMemoryShipmentRepository,
) {

    @PostMapping
    fun create(@RequestBody request: CreateOrderRequest): ResponseEntity<Order> {
        if (repository.existsById(request.orderId)) {
            throw ConflictException(
                ErrorCode.ORDER_ALREADY_EXISTS,
                "An order with id '${request.orderId}' already exists",
            )
        }

        val saved = repository.save(
            Order(request.orderId, request.customerId, request.amount, request.currency),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(saved)
    }

    @GetMapping("/{orderId}")
    fun getById(@PathVariable orderId: String): Order =
        repository.findById(orderId)
            ?: throw ResourceNotFoundException(
                ErrorCode.ORDER_NOT_FOUND,
                "No order with id '$orderId'",
            )

    @GetMapping
    fun list(): List<Order> = repository.findAll()

    @PostMapping("/{orderId}/cancel")
    fun cancel(@PathVariable orderId: String): Order {
        val order = repository.findById(orderId)
            ?: throw ResourceNotFoundException(
                ErrorCode.ORDER_NOT_FOUND,
                "No order with id '$orderId'",
            )

        if (order.status == OrderStatus.CANCELLED) {
            return order
        } else if (order.status == OrderStatus.ACTIVE) {
            val related = shipments.findByOrderId(orderId)
            val accepted = related.firstOrNull { it.status == ShipmentStatus.CONFIRMED }
            if (accepted != null) {
                throw ConflictException(
                    ErrorCode.ORDER_NOT_CANCELLABLE,
                    "Order '$orderId' has shipment '${accepted.shipmentId}' accepted by the carrier and cannot be cancelled",
                )
            }
            related
                .filter { it.status == ShipmentStatus.CREATED }
                .forEach { shipments.save(it.copy(status = ShipmentStatus.CANCELLED)) }
            return repository.save(order.copy(status = OrderStatus.CANCELLED))
        } else {
            return order
        }
    }
}
