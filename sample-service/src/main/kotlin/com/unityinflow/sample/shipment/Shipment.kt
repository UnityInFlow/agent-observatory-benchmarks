package com.unityinflow.sample.shipment

/**
 * A shipment raised against an order.
 *
 * This is the baseline (pre-benchmark) shape. BE-003 asks an agent to add a confirm
 * transition; the baseline deliberately has no way to move a shipment out of [CREATED].
 */
data class Shipment(
    val shipmentId: String,
    val orderId: String,
    val carrier: String,
    val status: ShipmentStatus,
)

/**
 * The lifecycle of a shipment.
 *
 * [CANCELLED] is terminal. It is reachable in the fixture only by writing it directly to
 * the repository — there is no cancel endpoint — which is enough for a test to set up the
 * state without giving the benchmark a second unrelated feature to implement.
 */
enum class ShipmentStatus {
    CREATED,
    CONFIRMED,
    CANCELLED,
}

data class CreateShipmentRequest(
    val shipmentId: String,
    val orderId: String,
    val carrier: String,
)
