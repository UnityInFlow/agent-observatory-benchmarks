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
 * The shipment feature as it exists before BE-003.
 *
 * These are "the existing tests" for that benchmark: they must keep passing, and an agent
 * that breaks one has regressed the fixture rather than solved the task.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ShipmentControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var repository: InMemoryShipmentRepository

    @BeforeEach
    fun reset() = repository.clear()

    private fun createShipment(shipmentId: String) =
        post("/shipments")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """{"shipmentId":"$shipmentId","orderId":"O-1","carrier":"DHL"}""",
            )

    @Test
    fun `creates a shipment in CREATED status`() {
        mockMvc.perform(createShipment("S-1"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.shipmentId").value("S-1"))
            .andExpect(jsonPath("$.status").value("CREATED"))
    }

    @Test
    fun `rejects a duplicate shipment id with the error envelope`() {
        mockMvc.perform(createShipment("S-2")).andExpect(status().isCreated)

        mockMvc.perform(createShipment("S-2"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("SHIPMENT_ALREADY_EXISTS"))
    }

    @Test
    fun `returns a shipment by id`() {
        mockMvc.perform(createShipment("S-3")).andExpect(status().isCreated)

        mockMvc.perform(get("/shipments/S-3"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.carrier").value("DHL"))
    }

    @Test
    fun `reports an unknown shipment through the error envelope`() {
        mockMvc.perform(get("/shipments/S-missing"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("SHIPMENT_NOT_FOUND"))
    }

    @Test
    fun `lists shipments in id order`() {
        mockMvc.perform(createShipment("S-b")).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-a")).andExpect(status().isCreated)

        mockMvc.perform(get("/shipments"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].shipmentId").value("S-a"))
            .andExpect(jsonPath("$[1].shipmentId").value("S-b"))
    }
}
