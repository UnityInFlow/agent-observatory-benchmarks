package com.unityinflow.sample.shipment

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
 * Tests for the confirm-shipment endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConfirmShipmentTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var repository: InMemoryShipmentRepository

    @BeforeEach
    fun reset() = repository.clear()

    private fun createShipment(shipmentId: String) =
        post("/shipments")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"shipmentId":"$shipmentId","orderId":"O-1","carrier":"DHL"}""")

    @Test
    fun `confirming a created shipment reports CONFIRMED and persists it`() {
        mockMvc.perform(createShipment("S-1")).andExpect(status().isCreated)

        mockMvc.perform(post("/shipments/S-1/confirm"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CONFIRMED"))

        // Re-read rather than trust the body the mutating call returned: a controller can
        // report the new state without ever having saved it.
        mockMvc.perform(get("/shipments/S-1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
    }

    @Test
    fun `a repeated confirm succeeds and reports the same state`() {
        mockMvc.perform(createShipment("S-2")).andExpect(status().isCreated)

        mockMvc.perform(post("/shipments/S-2/confirm"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CONFIRMED"))

        // The whole point of the ticket: the second call is a success, not a conflict, and
        // reports the same thing the first did.
        mockMvc.perform(post("/shipments/S-2/confirm"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.shipmentId").value("S-2"))
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
    }

    @Test
    fun `an unknown shipment is refused through the service error envelope`() {
        mockMvc.perform(post("/shipments/NOPE/confirm"))
            .andExpect(status().isNotFound)
            // Asserting the envelope, not just the status: Spring's default error body
            // carries the same 404 and would satisfy a status-only assertion.
            .andExpect(jsonPath("$.error.code").value("SHIPMENT_NOT_FOUND"))
    }
}
