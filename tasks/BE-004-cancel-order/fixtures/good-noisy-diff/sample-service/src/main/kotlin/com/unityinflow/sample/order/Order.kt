package com.unityinflow.sample.order

import java.math.BigDecimal

/**
 * An order placed by a customer.
 *
 * This is the baseline (pre-benchmark) shape. BE-002 asks an agent to reject orders
 * whose amount is not positive; the baseline deliberately accepts any amount.
 */
data class Order(
    val orderId: String,
    val customerId: String,
    val amount: BigDecimal,
    val currency: String,
    val status: OrderStatus = OrderStatus.ACTIVE,
)

/**
 * The lifecycle of an order.
 *
 * [CANCELLED] is terminal: nothing moves an order back, and no shipment may be raised
 * against it.
 */
enum class OrderStatus {
    ACTIVE,
    CANCELLED,
}

data class CreateOrderRequest(
    val orderId: String,
    val customerId: String,
    val amount: BigDecimal,
    val currency: String,
)
