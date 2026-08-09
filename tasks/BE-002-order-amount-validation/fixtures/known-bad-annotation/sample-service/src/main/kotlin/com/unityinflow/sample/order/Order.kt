package com.unityinflow.sample.order

import jakarta.validation.constraints.Positive
import java.math.BigDecimal

/**
 * Part of the *plausible but wrong* BE-002 submission: the obvious answer.
 *
 * `@Positive` plus `@Valid` on the controller is the textbook Spring solution and it does
 * produce HTTP 400 — through Spring's default error handling, which knows nothing about
 * this service's error envelope. The behaviour is right and the contract is broken, which
 * is exactly the failure this benchmark exists to detect.
 */
data class Order(
    val orderId: String,
    val customerId: String,
    val amount: BigDecimal,
    val currency: String,
)

data class CreateOrderRequest(
    val orderId: String,
    val customerId: String,
    @field:Positive(message = "amount must be greater than zero")
    val amount: BigDecimal,
    val currency: String,
)
