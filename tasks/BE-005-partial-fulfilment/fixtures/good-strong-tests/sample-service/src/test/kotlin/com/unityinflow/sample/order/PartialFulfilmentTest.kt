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
    fun `shipments allocate the order and the status follows the counts`() {
        mockMvc.perform(createOrder("PF-O-1", 5)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("PF-S-1a", "PF-O-1", 2)).andExpect(status().isCreated)

        mockMvc.perform(get("/orders/O-1"))
            .andExpect(jsonPath("$.fulfilment.allocated").value(2))
            .andExpect(jsonPath("$.fulfilment.status").value("PARTIALLY_ALLOCATED"))

        mockMvc.perform(createShipment("PF-S-1b", "PF-O-1", 3)).andExpect(status().isCreated)
        mockMvc.perform(get("/orders/O-1"))
            .andExpect(jsonPath("$.fulfilment.allocated").value(5))
            .andExpect(jsonPath("$.fulfilment.status").value("FULLY_ALLOCATED"))
    }

    @Test
    fun `over-allocation is refused through the envelope and stores nothing`() {
        mockMvc.perform(createOrder("PF-O-2", 2)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("PF-S-2a", "PF-O-2", 2)).andExpect(status().isCreated)

        mockMvc.perform(createShipment("PF-S-2b", "PF-O-2", 1))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("ORDER_OVER_ALLOCATED"))

        mockMvc.perform(get("/shipments/S-2b")).andExpect(status().isNotFound)
        mockMvc.perform(get("/orders/O-2")).andExpect(jsonPath("$.fulfilment.allocated").value(2))
    }

    @Test
    fun `cancelling a shipment releases its quantity and the status regresses`() {
        mockMvc.perform(createOrder("PF-O-3", 4)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("PF-S-3a", "PF-O-3", 2)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("PF-S-3b", "PF-O-3", 2)).andExpect(status().isCreated)
        mockMvc.perform(get("/orders/O-3")).andExpect(jsonPath("$.fulfilment.status").value("FULLY_ALLOCATED"))

        mockMvc.perform(post("/shipments/S-3a/cancel")).andExpect(status().isOk)

        mockMvc.perform(get("/orders/O-3"))
            .andExpect(jsonPath("$.fulfilment.allocated").value(2))
            .andExpect(jsonPath("$.fulfilment.status").value("PARTIALLY_ALLOCATED"))
        mockMvc.perform(createShipment("PF-S-3c", "PF-O-3", 2)).andExpect(status().isCreated)
        mockMvc.perform(get("/orders/O-3")).andExpect(jsonPath("$.fulfilment.status").value("FULLY_ALLOCATED"))
    }

    @Test
    fun `a delivered order reports DELIVERED and a wrong transition changes nothing`() {
        mockMvc.perform(createOrder("PF-O-4", 1)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("PF-S-4", "PF-O-4", 1)).andExpect(status().isCreated)

        mockMvc.perform(post("/shipments/S-4/deliver"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("SHIPMENT_STATE_CONFLICT"))
        mockMvc.perform(get("/shipments/S-4")).andExpect(jsonPath("$.status").value("CREATED"))

        mockMvc.perform(post("/shipments/S-4/confirm")).andExpect(status().isOk)
        mockMvc.perform(post("/shipments/S-4/deliver")).andExpect(status().isOk)
        mockMvc.perform(get("/orders/O-4"))
            .andExpect(jsonPath("$.fulfilment.delivered").value(1))
            .andExpect(jsonPath("$.fulfilment.status").value("DELIVERED"))
    }

    @Test
    fun `an order for an unknown customer is refused through the envelope and not stored`() {
        mockMvc.perform(createOrder("PF-O-5", 1, customerId = "C-nobody"))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error.code").value("CUSTOMER_NOT_FOUND"))
        mockMvc.perform(get("/orders/O-5")).andExpect(status().isNotFound)
    }

    @Test
    fun `lists page in id order and carry the total`() {
        for (i in 1..3) mockMvc.perform(createOrder("O-$i", 1)).andExpect(status().isCreated)

        mockMvc.perform(get("/orders").param("limit", "1").param("offset", "1"))
            .andExpect(status().isOk)
            .andExpect(header().string("X-Total-Count", "3"))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].orderId").value("PF-O-2"))

        mockMvc.perform(get("/orders").param("limit", "0"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.fields[0].field").value("limit"))
    }
}
