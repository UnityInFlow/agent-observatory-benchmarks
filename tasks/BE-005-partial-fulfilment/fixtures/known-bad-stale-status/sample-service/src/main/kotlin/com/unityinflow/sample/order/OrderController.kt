package com.unityinflow.sample.order

import com.unityinflow.sample.api.ConflictException
import com.unityinflow.sample.api.ErrorCode
import com.unityinflow.sample.api.FieldViolation
import com.unityinflow.sample.api.PageQuery
import com.unityinflow.sample.api.ResourceNotFoundException
import com.unityinflow.sample.api.UnprocessableEntityException
import com.unityinflow.sample.api.ValidationException
import com.unityinflow.sample.api.page
import com.unityinflow.sample.customer.InMemoryCustomerRepository
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
    private val customers: InMemoryCustomerRepository,
) {

    @PostMapping
    fun create(@RequestBody request: CreateOrderRequest): ResponseEntity<Order> {
        if (request.quantity < 1) {
            throw ValidationException(
                "Order quantity must be positive",
                listOf(FieldViolation("quantity", "must be at least 1")),
            )
        }
        if (customers.findById(request.customerId) == null) {
            throw UnprocessableEntityException(
                ErrorCode.CUSTOMER_NOT_FOUND,
                "No customer with id '${request.customerId}'",
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
    fun list(
        @RequestParam(required = false) fulfilment: String?,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) offset: Int?,
    ): ResponseEntity<List<Order>> {
        val wanted = fulfilment?.let { parseFulfilmentStatus(it) }
        return repository.findAll()
            .filter { wanted == null || it.fulfilment.status == wanted }
            .page(PageQuery.of(limit, offset))
    }

    /**
     * The order's quantity can change after shipments exist. The stored fulfilment is
     * re-derived against the new quantity here — the fourth write site.
     */
    @PutMapping("/{orderId}/quantity")
    fun amendQuantity(@PathVariable orderId: String, @RequestBody request: AmendQuantityRequest): Order {
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
        if (request.quantity < order.fulfilment.allocated) {
            throw ConflictException(
                ErrorCode.ORDER_QUANTITY_BELOW_ALLOCATED,
                "Order '$orderId' has ${order.fulfilment.allocated} allocated; its quantity cannot be reduced to ${request.quantity}",
            )
        }
        return repository.save(
            order.copy(quantity = request.quantity, fulfilment = order.fulfilment.updated(request.quantity)),
        )
    }

    private fun parseFulfilmentStatus(value: String): FulfilmentStatus =
        FulfilmentStatus.entries.firstOrNull { it.name == value }
            ?: throw ValidationException(
                "Unknown fulfilment status '$value'",
                listOf(FieldViolation("fulfilment", "must be one of ${FulfilmentStatus.entries.joinToString()}")),
            )
}
