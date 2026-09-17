package com.unityinflow.sample

import com.unityinflow.sample.customer.InMemoryCustomerRepository
import com.unityinflow.sample.order.InMemoryOrderRepository
import com.unityinflow.sample.shipment.InMemoryShipmentRepository
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
 * Evaluator-owned functional suite for BE-005 part 2 — an order needs its customer (AC3).
 *
 * This is the epic's update to EXISTING behaviour: the baseline accepts an order for any
 * customer id, and the baseline `OrderControllerTest` depends on that. The evaluator exempts
 * that one test class from AC2 for this task, because the ticket tells the agent to change
 * it; everything it covered is covered again here, with the customer created first.
 *
 * Response *shape* is checked separately by BE005ContractTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BE005CustomerRuleTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var customers: InMemoryCustomerRepository
    @Autowired lateinit var orders: InMemoryOrderRepository
    @Autowired lateinit var shipments: InMemoryShipmentRepository

    @BeforeEach
    fun reset() {
        customers.clear()
        orders.clear()
        shipments.clear()
    }

    private fun createCustomer(customerId: String) =
        post("/customers")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"customerId":"$customerId","name":"Grace Hopper","email":"grace@example.com"}""")

    private fun createOrder(orderId: String, customerId: String) =
        post("/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"orderId":"$orderId","customerId":"$customerId","amount":49.90,"currency":"EUR"}""")

    @Test
    fun `an order for an unknown customer is refused and not stored`() {
        mockMvc.perform(createOrder("O-1", "C-nobody")).andExpect(status().isUnprocessableEntity)
        mockMvc.perform(get("/orders/O-1")).andExpect(status().isNotFound)
        mockMvc.perform(get("/orders")).andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `an order for an existing customer is accepted and readable`() {
        mockMvc.perform(createCustomer("C-2")).andExpect(status().isCreated)

        mockMvc.perform(createOrder("O-2", "C-2"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.orderId").value("O-2"))
            .andExpect(jsonPath("$.customerId").value("C-2"))

        mockMvc.perform(get("/orders/O-2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.currency").value("EUR"))
    }

    @Test
    fun `the customer is checked on every create, not only the first`() {
        mockMvc.perform(createCustomer("C-3")).andExpect(status().isCreated)
        mockMvc.perform(createOrder("O-3a", "C-3")).andExpect(status().isCreated)

        mockMvc.perform(createOrder("O-3b", "C-other")).andExpect(status().isUnprocessableEntity)
        mockMvc.perform(get("/orders")).andExpect(jsonPath("$.length()").value(1))
    }

    @Test
    fun `a duplicate order id is still a conflict when the customer exists`() {
        mockMvc.perform(createCustomer("C-4")).andExpect(status().isCreated)
        mockMvc.perform(createOrder("O-4", "C-4")).andExpect(status().isCreated)

        mockMvc.perform(createOrder("O-4", "C-4")).andExpect(status().isConflict)
    }

    @Test
    fun `an unknown order is still 404`() {
        mockMvc.perform(get("/orders/does-not-exist")).andExpect(status().isNotFound)
    }

    @Test
    fun `an unknown customer read is 404`() {
        mockMvc.perform(get("/customers/does-not-exist")).andExpect(status().isNotFound)
    }
}
