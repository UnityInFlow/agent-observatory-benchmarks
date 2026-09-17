package com.unityinflow.sample.order

import com.unityinflow.sample.api.ConflictException
import com.unityinflow.sample.api.ErrorCode
import com.unityinflow.sample.api.FieldViolation
import com.unityinflow.sample.api.PageQuery
import com.unityinflow.sample.api.ResourceNotFoundException
import com.unityinflow.sample.api.ValidationException
import com.unityinflow.sample.api.page
import com.unityinflow.sample.shipment.InMemoryShipmentRepository
import com.unityinflow.sample.shipment.allocatedQuantity
import com.unityinflow.sample.shipment.deliveredQuantity
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/orders")
class OrderController(
    private val repository: InMemoryOrderRepository,
    private val shipments: InMemoryShipmentRepository,
) {

    @PostMapping
    fun create(@RequestBody request: CreateOrderRequest): ResponseEntity<OrderResponse> {
        if (request.quantity < 1) {
            throw ValidationException(
                "Order quantity must be positive",
                listOf(FieldViolation("quantity", "must be at least 1")),
            )
        }
        if (repository.existsById(request.orderId)) {
            throw ConflictException(
                ErrorCode.ORDER_ALREADY_EXISTS,
                "An order with id '${request.orderId}' already exists",
            )
        }

        val saved = repository.save(
            Order(request.orderId, request.customerId, request.amount, request.currency, request.quantity),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.withFulfilment())
    }

    @GetMapping("/{orderId}")
    fun getById(@PathVariable orderId: String): OrderResponse =
        repository.findById(orderId)?.withFulfilment()
            ?: throw ResourceNotFoundException(
                ErrorCode.ORDER_NOT_FOUND,
                "No order with id '$orderId'",
            )

    @GetMapping
    fun list(
        @RequestParam(required = false) fulfilment: String?,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) offset: Int?,
    ): ResponseEntity<List<OrderResponse>> {
        val wanted = fulfilment?.let { parseFulfilmentStatus(it) }
        return repository.findAll()
            .map { it.withFulfilment() }
            .filter { wanted == null || it.fulfilment.status == wanted }
            .page(PageQuery.of(limit, offset))
    }

    /**
     * The order's quantity can change after shipments exist. Fulfilment is not stored, so
     * nothing here has to be recomputed: the next read compares the shipments against the
     * new quantity, and the allocation guard does the same.
     */
    @PutMapping("/{orderId}/quantity")
    fun amendQuantity(@PathVariable orderId: String, @RequestBody request: AmendQuantityRequest): OrderResponse {
        if (request.quantity < 1) {
            throw ValidationException(
                "Order quantity must be positive",
                listOf(FieldViolation("quantity", "must be at least 1")),
            )
        }
        val order = repository.findById(orderId)
            ?: throw ResourceNotFoundException(
                ErrorCode.ORDER_NOT_FOUND,
                "No order with id '$orderId'",
            )
        val allocated = shipments.findByOrderId(orderId).allocatedQuantity()
        if (request.quantity < allocated) {
            throw ConflictException(
                ErrorCode.ORDER_QUANTITY_BELOW_ALLOCATED,
                "Order '$orderId' has $allocated allocated; its quantity cannot be reduced to ${request.quantity}",
            )
        }
        return repository.save(order.copy(quantity = request.quantity)).withFulfilment()
    }

    private fun parseFulfilmentStatus(value: String): FulfilmentStatus =
        FulfilmentStatus.entries.firstOrNull { it.name == value }
            ?: throw ValidationException(
                "Unknown fulfilment status '$value'",
                listOf(FieldViolation("fulfilment", "must be one of ${FulfilmentStatus.entries.joinToString()}")),
            )

    /**
     * The one place fulfilment is computed. Reading it from the shipments every time is
     * what makes a cancelled shipment's quantity come back without anyone remembering to
     * release it.
     */
    private fun Order.withFulfilment(): OrderResponse {
        val related = shipments.findByOrderId(orderId)
        val allocated = related.allocatedQuantity()
        val delivered = related.deliveredQuantity()
        val status = when {
            delivered >= quantity -> FulfilmentStatus.DELIVERED
            allocated >= quantity -> FulfilmentStatus.FULLY_ALLOCATED
            allocated == 0 -> FulfilmentStatus.UNALLOCATED
            else -> FulfilmentStatus.PARTIALLY_ALLOCATED
        }
        return OrderResponse(orderId, customerId, amount, currency, quantity, Fulfilment(allocated, delivered, status))
    }
}
