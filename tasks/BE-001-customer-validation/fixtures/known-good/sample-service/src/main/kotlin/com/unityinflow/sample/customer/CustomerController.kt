package com.unityinflow.sample.customer

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/customers")
class CustomerController(
    private val repository: InMemoryCustomerRepository,
) {

    @PostMapping
    fun create(@Valid @RequestBody request: CreateCustomerRequest): ResponseEntity<Customer> {
        val saved = repository.save(Customer(request.customerId, request.name, request.email))
        return ResponseEntity.status(HttpStatus.CREATED).body(saved)
    }

    @GetMapping("/{customerId}")
    fun getById(@PathVariable customerId: String): ResponseEntity<Customer> =
        repository.findById(customerId)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @GetMapping
    fun list(): List<Customer> = repository.findAll()
}
