package com.unityinflow.sample.customer

import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Repository

/**
 * Deliberately in-memory: the benchmark fixture must start with `./mvnw test` and no
 * external infrastructure, so an agent run is never blocked on a database container.
 */
@Repository
class InMemoryCustomerRepository {

    private val store = ConcurrentHashMap<String, Customer>()

    fun save(customer: Customer): Customer {
        store[customer.customerId] = customer
        return customer
    }

    fun findById(customerId: String): Customer? = store[customerId]

    fun findAll(): List<Customer> = store.values.sortedBy { it.customerId }

    fun clear() = store.clear()
}
