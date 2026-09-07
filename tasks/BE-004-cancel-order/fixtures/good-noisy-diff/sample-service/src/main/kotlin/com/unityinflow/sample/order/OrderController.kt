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
        if (repository.existsById(request.orderId)) throw ConflictException(ErrorCode.ORDER_ALREADY_EXISTS, "An order with id '${request.orderId}' already exists")
        return ResponseEntity.status(HttpStatus.CREATED).body(repository.save(Order(request.orderId, request.customerId, request.amount, request.currency)))
    }

    @GetMapping("/{orderId}")
    fun getById(@PathVariable orderId: String): Order {
        val order = repository.findById(orderId)
        if (order == null) {
            throw ResourceNotFoundException(ErrorCode.ORDER_NOT_FOUND, "No order with id '$orderId'")
        }
        return order
    }

    @GetMapping
    fun list(): List<Order> {
        val all = repository.findAll()
        return all
    }

    @PostMapping("/{orderId}/cancel")
    fun cancel(@PathVariable orderId: String): Order {
        val order = repository.findById(orderId)
            ?: throw ResourceNotFoundException(
                ErrorCode.ORDER_NOT_FOUND,
                "No order with id '$orderId'",
            )

        return when (order.status) {
            // Already in the target state: the transition has nothing left to do, so the
            // call succeeds and reports the current order. This is what makes a retry
            // safe, and it is deliberately not a conflict.
            OrderStatus.CANCELLED -> order

            OrderStatus.ACTIVE -> {
                val related = shipments.findByOrderId(orderId)

                // Decide before touching anything. A cancel refused because the carrier
                // has accepted a shipment must leave every shipment exactly as it was, so
                // the check runs over the whole set before the first write.
                related.firstOrNull { it.status == ShipmentStatus.CONFIRMED }?.let {
                    throw ConflictException(
                        ErrorCode.ORDER_NOT_CANCELLABLE,
                        "Order '$orderId' has shipment '${it.shipmentId}' accepted by the carrier and cannot be cancelled",
                    )
                }

                related
                    .filter { it.status == ShipmentStatus.CREATED }
                    .forEach { shipments.save(it.copy(status = ShipmentStatus.CANCELLED)) }

                repository.save(order.copy(status = OrderStatus.CANCELLED))
            }
        }
    }
}
