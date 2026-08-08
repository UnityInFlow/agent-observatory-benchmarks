package com.unityinflow.sample.customer

import jakarta.validation.constraints.NotBlank

data class Customer(
    val customerId: String,
    val name: String,
    val email: String,
)

data class CreateCustomerRequest(
    @field:NotBlank(message = "customerId must not be blank")
    val customerId: String,
    val name: String,
    val email: String,
)
