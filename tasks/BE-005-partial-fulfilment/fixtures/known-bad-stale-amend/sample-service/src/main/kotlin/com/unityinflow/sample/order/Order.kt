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
    val fulfilment: Fulfilment = Fulfilment(0, 0, FulfilmentStatus.UNALLOCATED),
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

/** How much of an order's quantity its shipments account for. */
data class Fulfilment(
    val allocated: Int,
    val delivered: Int,
    val status: FulfilmentStatus,
) {
    /** The fulfilment after the counts moved, with the status re-derived from them. */
    fun updated(quantity: Int, allocatedDelta: Int = 0, deliveredDelta: Int = 0): Fulfilment {
        val allocated = this.allocated + allocatedDelta
        val delivered = this.delivered + deliveredDelta
        val status = when {
            delivered >= quantity -> FulfilmentStatus.DELIVERED
            allocated >= quantity -> FulfilmentStatus.FULLY_ALLOCATED
            allocated == 0 -> FulfilmentStatus.UNALLOCATED
            else -> FulfilmentStatus.PARTIALLY_ALLOCATED
        }
        return Fulfilment(allocated, delivered, status)
    }
}

enum class FulfilmentStatus {
    UNALLOCATED,
    PARTIALLY_ALLOCATED,
    FULLY_ALLOCATED,
    DELIVERED,
}
