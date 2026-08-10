package com.unityinflow.sample.shipment

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Evaluator-owned contract suite for BE-003 — *how* the endpoint reports failure (AC4).
 *
 * Run only after BE003FunctionalTest passes: if nothing rejects anything, there is no
 * error response whose shape could be judged.
 *
 * The trap here is the same one BE-002 found, in a new place. `ResponseStatusException`
 * and a bare `ResponseEntity.status(...)` both produce the right status code through
 * Spring's default error handling, whose body is `{timestamp, status, error, path}` — no
 * `error.code`, no `error.message`. This service answers with [ApiError] instead, and the
 * convention is discoverable: every other failure in the shipment and order features uses
 * it, and the existing tests assert on it. The task does not restate it, exactly as a real
 * ticket would not.
 *
 * The 404 asserts the exact code, because `SHIPMENT_NOT_FOUND` already exists in the
 * fixture and the GET endpoint already uses it — reusing it is the discoverable answer.
 *
 * The 409 deliberately does NOT assert a code name. The fixture has no code for "cannot
 * confirm from this state", so the agent has to add one, and pinning a name would be
 * grading a guess at vocabulary rather than adherence to the contract. What is required is
 * that the failure travels through the envelope at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BE003ContractTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var repository: InMemoryShipmentRepository

    @BeforeEach
    fun reset() = repository.clear()

    @Test
    fun `an unknown shipment is reported through the error envelope`() {
        mockMvc.perform(post("/shipments/S-missing/confirm"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").exists())
            .andExpect(jsonPath("$.error.code").value("SHIPMENT_NOT_FOUND"))
            .andExpect(jsonPath("$.error.message").isNotEmpty)
    }

    @Test
    fun `an unconfirmable state is reported through the error envelope`() {
        repository.save(Shipment("S-7", "O-1", "DHL", ShipmentStatus.CANCELLED))

        mockMvc.perform(post("/shipments/S-7/confirm"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").exists())
            .andExpect(jsonPath("$.error.code").isNotEmpty)
            .andExpect(jsonPath("$.error.message").isNotEmpty)
    }

    @Test
    fun `error responses do not fall back to the framework default shape`() {
        repository.save(Shipment("S-8", "O-1", "DHL", ShipmentStatus.CANCELLED))

        // Spring's default error body carries a top-level `path`; the service envelope
        // does not. Asserting its absence catches the case where a handler was added but
        // the failure still escapes through the framework.
        mockMvc.perform(post("/shipments/S-8/confirm"))
            .andExpect(jsonPath("$.path").doesNotExist())
            .andExpect(jsonPath("$.timestamp").doesNotExist())
    }
}
