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
    val quantity: Int = 1,
)

data class CreateOrderRequest(
    val orderId: String,
    val customerId: String,
    val amount: BigDecimal,
    val currency: String,
    val quantity: Int = 1,
)

/**
 * The body of `PUT /orders/{orderId}/quantity`. An absent quantity deserialises to 0 and is
 * refused the same way a non-positive one is, through the envelope.
 */
data class AmendQuantityRequest(
    val quantity: Int = 0,
)

/**
 * How much of an order's quantity its shipments account for.
 *
 * Not stored: it is a view over the order's shipments, computed when the order is read,
 * so it is always consistent with them and no shipment transition has to remember to
 * update it.
 */
data class Fulfilment(
    val allocated: Int,
    val delivered: Int,
    val status: FulfilmentStatus,
)

enum class FulfilmentStatus {
    UNALLOCATED,
    PARTIALLY_ALLOCATED,
    FULLY_ALLOCATED,
    DELIVERED,
}

/** An order as the API reports it: the stored order plus its fulfilment. */
data class OrderResponse(
    val orderId: String,
    val customerId: String,
    val amount: BigDecimal,
    val currency: String,
    val quantity: Int,
    val fulfilment: Fulfilment,
)
