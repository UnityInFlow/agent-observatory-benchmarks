package com.unityinflow.sample.shipment

import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Repository

/**
 * In-memory for the same reason as the order store: a benchmark fixture must start with
 * `./mvnw test` and no external infrastructure.
 */
@Repository
class InMemoryShipmentRepository {

    private val store = ConcurrentHashMap<String, Shipment>()

    fun save(shipment: Shipment): Shipment {
        store[shipment.shipmentId] = shipment
        return shipment
    }

    fun existsById(shipmentId: String): Boolean = store.containsKey(shipmentId)

    fun findById(shipmentId: String): Shipment? = store[shipmentId]

    fun findAll(): List<Shipment> = store.values.sortedBy { it.shipmentId }

    fun clear() = store.clear()
}
