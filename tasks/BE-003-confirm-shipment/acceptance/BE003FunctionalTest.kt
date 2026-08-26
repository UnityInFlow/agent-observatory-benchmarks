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
 * Evaluator-owned functional suite for BE-003 — *what* the endpoint does (AC3, AC5).
 *
 * This file is NOT part of the fixture. The evaluator copies it into the service at
 * evaluation time and removes it afterwards, so correctness never depends on the agent
 * having written the right test itself.
 *
 * The discriminating case is `confirming twice is idempotent`. `ConflictException` already
 * exists in this service and a repeated confirm reads like a conflict, so 409 is the
 * natural wrong answer — and the task explicitly requires the repeat to succeed. An
 * implementation that returns 409 there is a real requirement failure, not a style
 * disagreement, and it is what separates a submission that read the requirement from one
 * that pattern-matched the surrounding code.
 *
 * Response *shape* is checked separately by BE003ContractTest, so a submission with the
 * right behaviour and the wrong error envelope is reported as a different kind of failure.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BE003FunctionalTest {

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

    private fun confirm(shipmentId: String) = post("/shipments/$shipmentId/confirm")

    @Test
    fun `confirming a created shipment returns 200 and reports CONFIRMED`() {
        mockMvc.perform(createShipment("S-1")).andExpect(status().isCreated)

        mockMvc.perform(confirm("S-1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.shipmentId").value("S-1"))
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
    }

    @Test
    fun `the confirmed status is persisted, not just returned`() {
        mockMvc.perform(createShipment("S-2")).andExpect(status().isCreated)
        mockMvc.perform(confirm("S-2")).andExpect(status().isOk)

        mockMvc.perform(get("/shipments/S-2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
    }

    @Test
    fun `confirming twice is idempotent`() {
        mockMvc.perform(createShipment("S-3")).andExpect(status().isCreated)

        mockMvc.perform(confirm("S-3"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CONFIRMED"))

        // The repeat is not an error. Same status code, same resulting state.
        mockMvc.perform(confirm("S-3"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.shipmentId").value("S-3"))
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
    }

    @Test
    fun `confirming does not duplicate or lose the shipment`() {
        mockMvc.perform(createShipment("S-4")).andExpect(status().isCreated)
        mockMvc.perform(confirm("S-4")).andExpect(status().isOk)
        mockMvc.perform(confirm("S-4")).andExpect(status().isOk)

        mockMvc.perform(get("/shipments"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].status").value("CONFIRMED"))
    }

    @Test
    fun `confirming a cancelled shipment is rejected with 409`() {
        repository.save(Shipment("S-5", "O-1", "DHL", ShipmentStatus.CANCELLED))

        mockMvc.perform(confirm("S-5")).andExpect(status().isConflict)

        // And it stays cancelled.
        mockMvc.perform(get("/shipments/S-5"))
            .andExpect(jsonPath("$.status").value("CANCELLED"))
    }

    @Test
    fun `confirming an unknown shipment returns 404`() {
        mockMvc.perform(confirm("S-missing")).andExpect(status().isNotFound)
    }

    @Test
    fun `creating a shipment still works`() {
        mockMvc.perform(createShipment("S-6"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("CREATED"))
    }
}
