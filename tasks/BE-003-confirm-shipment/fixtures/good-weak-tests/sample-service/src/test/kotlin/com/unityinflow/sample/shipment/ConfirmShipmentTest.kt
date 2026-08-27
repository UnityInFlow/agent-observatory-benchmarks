package com.unityinflow.sample.shipment

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
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
    fun `confirm works`() {
        mockMvc.perform(createShipment("S-1")).andExpect(status().isCreated)
        mockMvc.perform(post("/shipments/S-1/confirm")).andExpect(status().isOk)
    }

    @Test
    fun `unknown shipment returns 404`() {
        mockMvc.perform(post("/shipments/NOPE/confirm")).andExpect(status().isNotFound)
    }
}
