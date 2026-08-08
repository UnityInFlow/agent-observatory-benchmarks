package com.unityinflow.sample.order

import com.unityinflow.sample.api.ConflictException
import com.unityinflow.sample.api.ErrorCode
import com.unityinflow.sample.api.FieldViolation
import com.unityinflow.sample.api.ResourceNotFoundException
import com.unityinflow.sample.api.ValidationException
import java.math.BigDecimal
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * A correct BE-002 submission, used to prove the evaluator accepts good work.
 *
 * The rejection is raised as a [ValidationException], so [com.unityinflow.sample.api.GlobalExceptionHandler]
 * renders it in the same envelope as the 404 and 409 responses that were already here.
 */
@RestController
@RequestMapping("/orders")
class OrderController(
    private val repository: InMemoryOrderRepository,
) {

    @PostMapping
    fun create(@RequestBody request: CreateOrderRequest): ResponseEntity<Order> {
        if (request.amount <= BigDecimal.ZERO) {
            throw ValidationException(
                "amount must be greater than zero",
                listOf(FieldViolation("amount", "must be greater than zero")),
            )
        }

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
