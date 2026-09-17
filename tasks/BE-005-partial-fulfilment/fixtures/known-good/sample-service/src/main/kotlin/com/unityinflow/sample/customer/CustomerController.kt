package com.unityinflow.sample.customer

import com.unityinflow.sample.api.ErrorCode
import com.unityinflow.sample.api.PageQuery
import com.unityinflow.sample.api.ResourceNotFoundException
import com.unityinflow.sample.api.page
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/customers")
class CustomerController(
    private val repository: InMemoryCustomerRepository,
) {

    @PostMapping
    fun create(@RequestBody request: CreateCustomerRequest): ResponseEntity<Customer> {
        val saved = repository.save(Customer(request.customerId, request.name, request.email))
        return ResponseEntity.status(HttpStatus.CREATED).body(saved)
    }

    @GetMapping("/{customerId}")
    fun getById(@PathVariable customerId: String): Customer =
        repository.findById(customerId)
            ?: throw ResourceNotFoundException(
                ErrorCode.CUSTOMER_NOT_FOUND,
                "No customer with id '$customerId'",
            )

    @GetMapping
    fun list(
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) offset: Int?,
    ): ResponseEntity<List<Customer>> =
        repository.findAll().page(PageQuery.of(limit, offset))
}
