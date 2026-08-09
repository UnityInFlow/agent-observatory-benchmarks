package com.unityinflow.sample.order

import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Repository

/**
 * In-memory for the same reason as the customer store: a benchmark fixture must start
 * with `./mvnw test` and no external infrastructure.
 */
@Repository
class InMemoryOrderRepository {

    private val store = ConcurrentHashMap<String, Order>()

    fun save(order: Order): Order {
        store[order.orderId] = order
        return order
    }

    fun existsById(orderId: String): Boolean = store.containsKey(orderId)

    fun findById(orderId: String): Order? = store[orderId]

    fun findAll(): List<Order> = store.values.sortedBy { it.orderId }

    fun clear() = store.clear()
}
