package com.unityinflow.sample.order

import com.unityinflow.sample.customer.InMemoryCustomerRepository
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
class PartialFulfilmentTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var customers: InMemoryCustomerRepository
    @Autowired lateinit var orders: InMemoryOrderRepository
    @Autowired lateinit var shipments: InMemoryShipmentRepository

    @BeforeEach
    fun reset() {
        customers.clear()
        orders.clear()
        shipments.clear()
        mockMvc.perform(
            post("/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"customerId":"PF-C-1","name":"Ada Lovelace","email":"ada@example.com"}"""),
        ).andExpect(status().isCreated)
    }

    private fun createOrder(orderId: String, quantity: Int, customerId: String = "PF-C-1") =
        post("/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"orderId":"$orderId","customerId":"$customerId","amount":49.90,"currency":"EUR","quantity":$quantity}""")

    private fun createShipment(shipmentId: String, orderId: String, quantity: Int) =
        post("/shipments")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"shipmentId":"$shipmentId","orderId":"$orderId","carrier":"DHL","quantity":$quantity}""")

    @Test
    fun `creates an order with a quantity`() {
        mockMvc.perform(createOrder("PF-O-1", 5)).andExpect(status().isCreated)
    }

    @Test
    fun `creates a shipment for an order`() {
        mockMvc.perform(createOrder("PF-O-2", 5)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("PF-S-2", "PF-O-2", 2)).andExpect(status().isCreated)
    }

    @Test
    fun `refuses over-allocation`() {
        mockMvc.perform(createOrder("PF-O-3", 2)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("PF-S-3a", "PF-O-3", 2)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("PF-S-3b", "PF-O-3", 1)).andExpect(status().isConflict)
    }

    @Test
    fun `confirms, delivers and cancels`() {
        mockMvc.perform(createOrder("PF-O-4", 2)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("PF-S-4a", "PF-O-4", 1)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("PF-S-4b", "PF-O-4", 1)).andExpect(status().isCreated)
        mockMvc.perform(post("/shipments/S-4a/confirm")).andExpect(status().isOk)
        mockMvc.perform(post("/shipments/S-4a/deliver")).andExpect(status().isOk)
        mockMvc.perform(post("/shipments/S-4b/cancel")).andExpect(status().isOk)
    }

    @Test
    fun `refuses an order for an unknown customer`() {
        mockMvc.perform(createOrder("PF-O-5", 1, customerId = "C-nobody")).andExpect(status().isUnprocessableEntity)
    }

    @Test
    fun `lists orders`() {
        mockMvc.perform(createOrder("PF-O-6", 1)).andExpect(status().isCreated)
        mockMvc.perform(get("/orders").param("limit", "10")).andExpect(status().isOk)
    }
}
