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
    fun `cancelling an active order reports CANCELLED and persists it`() {
        mockMvc.perform(createOrder("O-1")).andExpect(status().isCreated)

        mockMvc.perform(cancel("O-1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELLED"))

        // Re-read rather than trust the body the mutating call returned: a controller can
        // report the new state without ever having saved it.
        mockMvc.perform(get("/orders/O-1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELLED"))
    }

    @Test
    fun `cancelling cascades to the order's CREATED shipments`() {
        mockMvc.perform(createOrder("O-2")).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-2", "O-2")).andExpect(status().isCreated)

        mockMvc.perform(cancel("O-2")).andExpect(status().isOk)

        mockMvc.perform(get("/shipments/S-2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELLED"))
    }

    @Test
    fun `a repeated cancel succeeds and reports the same state`() {
        mockMvc.perform(createOrder("O-3")).andExpect(status().isCreated)

        mockMvc.perform(cancel("O-3"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELLED"))

        // The second call is a success, not a conflict, and reports the same thing the
        // first did.
        mockMvc.perform(cancel("O-3"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.orderId").value("O-3"))
            .andExpect(jsonPath("$.status").value("CANCELLED"))
    }

    @Test
    fun `a cancel blocked by an accepted shipment is refused and changes nothing`() {
        mockMvc.perform(createOrder("O-4")).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-4a", "O-4")).andExpect(status().isCreated)
        confirmedShipment("S-4b", "O-4")

        mockMvc.perform(cancel("O-4"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("ORDER_NOT_CANCELLABLE"))
            .andExpect(jsonPath("$.error.message").isNotEmpty)

        // All-or-nothing: the shipment that sorted before the accepted one is untouched.
        mockMvc.perform(get("/orders/O-4")).andExpect(jsonPath("$.status").value("ACTIVE"))
        mockMvc.perform(get("/shipments/S-4a")).andExpect(jsonPath("$.status").value("CREATED"))
        mockMvc.perform(get("/shipments/S-4b")).andExpect(jsonPath("$.status").value("CONFIRMED"))
    }

    @Test
    fun `an unknown order is refused with the error envelope`() {
        mockMvc.perform(cancel("O-missing"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"))
            .andExpect(jsonPath("$.error.message").isNotEmpty)
    }

    @Test
    fun `a cancelled order refuses new shipments with the error envelope`() {
        mockMvc.perform(createOrder("O-5")).andExpect(status().isCreated)
        mockMvc.perform(cancel("O-5")).andExpect(status().isOk)

        mockMvc.perform(createShipment("S-5", "O-5"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("ORDER_CANCELLED"))
            .andExpect(jsonPath("$.error.message").isNotEmpty)

        mockMvc.perform(get("/shipments/S-5")).andExpect(status().isNotFound)
    }
}
