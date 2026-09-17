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
import com.unityinflow.sample.shipment.InMemoryShipmentRepository
import com.unityinflow.sample.shipment.allocatedQuantity
import com.unityinflow.sample.shipment.deliveredQuantity
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
@RequestMapping("/orders")
class OrderController(
    private val repository: InMemoryOrderRepository,
    private val customers: InMemoryCustomerRepository,
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
        var status: FulfilmentStatus
        if (delivered >= quantity) {
            status = FulfilmentStatus.DELIVERED
        } else {
            if (allocated >= quantity) {
                status = FulfilmentStatus.FULLY_ALLOCATED
            } else {
                if (allocated == 0) {
                    status = FulfilmentStatus.UNALLOCATED
                } else {
                    if (allocated > 0) {
                        status = FulfilmentStatus.PARTIALLY_ALLOCATED
                    } else {
                        status = FulfilmentStatus.UNALLOCATED
                    }
                }
            }
        }
        return OrderResponse(orderId, customerId, amount, currency, quantity, Fulfilment(allocated, delivered, status))
    }
}
