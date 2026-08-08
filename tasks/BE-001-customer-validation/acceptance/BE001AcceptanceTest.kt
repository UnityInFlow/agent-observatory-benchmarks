package com.unityinflow.sample.customer

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Evaluator-owned acceptance suite for BE-001.
 *
 * This file is NOT part of the fixture. The evaluator copies it into the service at
 * evaluation time and removes it afterwards, so that correctness never depends on the
 * agent having written the right test itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BE001AcceptanceTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var repository: InMemoryCustomerRepository

    @BeforeEach
    fun reset() = repository.clear()

    /** AC3 — blank customerId must be rejected with HTTP 400. */
    @Test
    fun `rejects an empty customerId with 400`() {
        mockMvc.perform(
            post("/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"customerId":"","name":"Ada","email":"ada@example.com"}"""),
        ).andExpect(status().isBadRequest)
    }

    /** AC3 — whitespace only is also blank. */
    @Test
    fun `rejects a whitespace-only customerId with 400`() {
        mockMvc.perform(
            post("/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"customerId":"   ","name":"Ada","email":"ada@example.com"}"""),
        ).andExpect(status().isBadRequest)
    }

    /** AC4 — the happy path must keep working. */
    @Test
    fun `still accepts a valid customerId with 201`() {
        mockMvc.perform(
            post("/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"customerId":"C-100","name":"Ada","email":"ada@example.com"}"""),
        ).andExpect(status().isCreated)
    }
}
