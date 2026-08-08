package com.unityinflow.sample.order

import com.unityinflow.sample.api.ConflictException
import com.unityinflow.sample.api.ErrorCode
import com.unityinflow.sample.api.ResourceNotFoundException
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The other half of the *plausible but wrong* BE-002 submission — see `Order.kt`.
 */
@RestController
@RequestMapping("/orders")
class OrderController(
    private val repository: InMemoryOrderRepository,
) {

    @PostMapping
    fun create(@Valid @RequestBody request: CreateOrderRequest): ResponseEntity<Order> {
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
}
