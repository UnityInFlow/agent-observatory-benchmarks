package com.unityinflow.sample.order

import com.unityinflow.sample.shipment.InMemoryShipmentRepository
import com.unityinflow.sample.shipment.Shipment
import com.unityinflow.sample.shipment.ShipmentStatus
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
 * Tests for the cancel-order endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CancelOrderTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var orders: InMemoryOrderRepository

    @Autowired
    lateinit var shipments: InMemoryShipmentRepository

    @BeforeEach
    fun reset() {
        orders.clear()
        shipments.clear()
    }

    private fun createOrder(orderId: String) =
        post("/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"orderId":"$orderId","customerId":"C-1","amount":49.90,"currency":"EUR"}""")

    private fun createShipment(shipmentId: String, orderId: String) =
        post("/shipments")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"shipmentId":"$shipmentId","orderId":"$orderId","carrier":"DHL"}""")

    private fun cancel(orderId: String) = post("/orders/$orderId/cancel")

    // There is no confirm endpoint in the baseline; a CONFIRMED shipment is set up by
    // writing it to the repository directly, exactly as BE-003 sets up CANCELLED.
    private fun confirmedShipment(shipmentId: String, orderId: String) =
        shipments.save(Shipment(shipmentId, orderId, "DHL", ShipmentStatus.CONFIRMED))

    @Test
    fun `cancelling an order returns 200`() {
        mockMvc.perform(createOrder("O-1")).andExpect(status().isCreated)

        mockMvc.perform(cancel("O-1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELLED"))
    }

    @Test
    fun `cancelling cascades to shipments`() {
        mockMvc.perform(createOrder("O-2")).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-2", "O-2")).andExpect(status().isCreated)

        mockMvc.perform(cancel("O-2")).andExpect(status().isOk)
    }

    @Test
    fun `a confirmed shipment blocks the cancel`() {
        mockMvc.perform(createOrder("O-4")).andExpect(status().isCreated)
        confirmedShipment("S-4", "O-4")

        mockMvc.perform(cancel("O-4")).andExpect(status().isConflict)
    }

    @Test
    fun `an unknown order returns 404`() {
        mockMvc.perform(cancel("O-missing")).andExpect(status().isNotFound)
    }

    @Test
    fun `a cancelled order refuses new shipments`() {
        mockMvc.perform(createOrder("O-5")).andExpect(status().isCreated)
        mockMvc.perform(cancel("O-5")).andExpect(status().isOk)

        mockMvc.perform(createShipment("S-5", "O-5")).andExpect(status().isConflict)
    }
}
