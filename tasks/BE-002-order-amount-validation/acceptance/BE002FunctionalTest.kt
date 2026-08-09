package com.unityinflow.sample.order

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Evaluator-owned functional suite for BE-002 — *what* the endpoint does (AC3, AC5).
 *
 * This file is NOT part of the fixture. The evaluator copies it into the service at
 * evaluation time and removes it afterwards, so correctness never depends on the agent
 * having written the right test itself.
 *
 * The response *shape* is checked separately, by BE002ContractTest, so that a submission
 * which rejects the order correctly but ignores the service's error contract is reported
 * as a different kind of failure rather than as broken behaviour.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BE002FunctionalTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var repository: InMemoryOrderRepository

    @BeforeEach
    fun reset() = repository.clear()

    private fun createOrder(orderId: String, amount: String) =
        post("/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """{"orderId":"$orderId","customerId":"C-1","amount":$amount,"currency":"EUR"}""",
            )

    /** AC3 — zero is not a valid amount. */
    @Test
    fun `rejects an amount of zero with 400`() {
        mockMvc.perform(createOrder("O-ZERO", "0"))
            .andExpect(status().isBadRequest)
    }

    /** AC3 — neither is a negative amount. */
    @Test
    fun `rejects a negative amount with 400`() {
        mockMvc.perform(createOrder("O-NEG", "-12.50"))
            .andExpect(status().isBadRequest)
    }

    /** AC3 — a rejected order must not be stored. */
    @Test
    fun `does not store a rejected order`() {
        mockMvc.perform(createOrder("O-ZERO-2", "0"))
            .andExpect(status().isBadRequest)

        mockMvc.perform(get("/orders/O-ZERO-2"))
            .andExpect(status().isNotFound)
    }

    /** AC5 — the happy path must keep working. */
    @Test
    fun `still accepts a positive amount with 201`() {
        mockMvc.perform(createOrder("O-OK", "49.90"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.orderId").value("O-OK"))
    }

    /** AC5 — a small positive amount is still an amount. */
    @Test
    fun `still accepts a fractional amount with 201`() {
        mockMvc.perform(createOrder("O-SMALL", "0.01"))
            .andExpect(status().isCreated)
    }
}
