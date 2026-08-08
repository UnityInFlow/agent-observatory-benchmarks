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
 * Baseline behaviour of the order feature. BE-002 must not break any of these.
 *
 * Note how the failure cases assert the response *body*, not just the status: the error
 * envelope is part of the API contract, so a change that returns the right status with
 * the wrong shape is a breaking change for clients.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var repository: InMemoryOrderRepository

    @BeforeEach
    fun reset() = repository.clear()

    private fun createOrder(orderId: String, amount: String = "49.90") =
        post("/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """{"orderId":"$orderId","customerId":"C-1","amount":$amount,"currency":"EUR"}""",
            )

    @Test
    fun `creates an order and returns 201`() {
        mockMvc.perform(createOrder("O-1"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.orderId").value("O-1"))
    }

    @Test
    fun `reads back a created order`() {
        mockMvc.perform(createOrder("O-2")).andExpect(status().isCreated)

        mockMvc.perform(get("/orders/O-2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.currency").value("EUR"))
    }

    @Test
    fun `returns 404 with the error envelope for an unknown order`() {
        mockMvc.perform(get("/orders/does-not-exist"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"))
            .andExpect(jsonPath("$.error.message").isNotEmpty)
    }

    @Test
    fun `returns 409 with the error envelope for a duplicate order id`() {
        mockMvc.perform(createOrder("O-3")).andExpect(status().isCreated)

        mockMvc.perform(createOrder("O-3"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("ORDER_ALREADY_EXISTS"))
            .andExpect(jsonPath("$.error.message").isNotEmpty)
    }
}
