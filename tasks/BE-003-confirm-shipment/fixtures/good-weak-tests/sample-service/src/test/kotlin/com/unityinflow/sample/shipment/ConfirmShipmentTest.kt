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
 * Tests the submission author wrote for BE-003 — the WEAK version.
 *
 * QUALITY VARIANT: test-quality only. The production code is character-for-character
 * identical to known-good, so behaviour, architecture and diff size are all unchanged and
 * every gate passes. These tests pass too.
 *
 * What they fail to do is the point:
 *
 *  - the repeat is never exercised. confirm is called once per test, so the one behaviour
 *    the ticket states in bold is untested; a submission that 409s on retry would still be
 *    green here
 *  - refusals assert only the status code. A submission returning Spring's default error
 *    body carries the same 404 and passes unchanged, so the service's error contract is
 *    unprotected
 *  - persistence is never verified. Nothing re-reads the shipment, so a controller that
 *    reports CONFIRMED without saving would pass
 *
 * Every one of these gaps is invisible to the evaluator: it runs the tests and they pass.
 * Only a quality rubric can see that they assert almost nothing.
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
