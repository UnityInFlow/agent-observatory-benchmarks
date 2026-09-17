package com.unityinflow.sample.shipment

/**
 * A shipment raised against an order, carrying part of that order's quantity.
 */
data class Shipment(
    val shipmentId: String,
    val orderId: String,
    val carrier: String,
    val status: ShipmentStatus,
    val quantity: Int = 1,
)

/**
 * The lifecycle of a shipment: CREATED → CONFIRMED → DELIVERED, or CREATED → CANCELLED.
 *
 * [CANCELLED] and [DELIVERED] are terminal. A cancelled shipment no longer accounts for
 * any of its order's quantity.
 */
enum class ShipmentStatus {
    CREATED,
    CONFIRMED,
    DELIVERED,
    CANCELLED,
}

data class CreateShipmentRequest(
    val shipmentId: String,
    val orderId: String,
    val carrier: String,
    val quantity: Int = 1,
)

/** The quantity these shipments account for: everything that is not cancelled. */
fun List<Shipment>.allocatedQuantity(): Int =
    filter { it.status != ShipmentStatus.CANCELLED }.sumOf { it.quantity }

/** The quantity these shipments have delivered. */
fun List<Shipment>.deliveredQuantity(): Int =
    filter { it.status == ShipmentStatus.DELIVERED }.sumOf { it.quantity }
