package com.unityinflow.sample.customer

/**
 * A customer of the sample service.
 *
 * This is the baseline (pre-benchmark) shape. BE-001 asks an agent to reject blank
 * customer identifiers at the API boundary; the baseline deliberately does not.
 */
data class Customer(
    val customerId: String,
    val name: String,
    val email: String,
)

data class CreateCustomerRequest(
    val customerId: String,
    val name: String,
    val email: String,
)
