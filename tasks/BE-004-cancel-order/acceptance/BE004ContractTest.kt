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
 * Evaluator-owned contract suite for BE-004 — *how* the endpoints report failure (AC4).
 *
 * Run only after BE004FunctionalTest passes: if nothing rejects anything, there is no
 * error response whose shape could be judged.
 *
 * Same trap as BE-002 and BE-003, in two new places. `ResponseStatusException` and a bare
 * `ResponseEntity.status(...)` produce the right status through Spring's default error
 * handling, whose body is `{timestamp, status, error, path}` — no `error.code`. This
 * service answers with [ApiError] instead, and the convention is discoverable: every other
 * failure in the order and shipment features uses it, and the existing tests assert on it.
 *
 * The 404 asserts the exact code, because `ORDER_NOT_FOUND` already exists and the GET
 * endpoint already uses it. Neither 409 pins a code name: the fixture has no code for
 * "blocked by an accepted shipment" or "order is cancelled", so the agent has to add them,
 * and pinning a name would grade a guess at vocabulary rather than adherence to the
 * contract.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BE004ContractTest {

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
    fun `an unknown order is reported through the error envelope`() {
        mockMvc.perform(cancel("O-missing"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").exists())
            .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"))
            .andExpect(jsonPath("$.error.message").isNotEmpty)
    }

    @Test
    fun `a blocked cancel is reported through the error envelope`() {
        mockMvc.perform(createOrder("O-20")).andExpect(status().isCreated)
        confirmedShipment("S-20", "O-20")

        mockMvc.perform(cancel("O-20"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").exists())
            .andExpect(jsonPath("$.error.code").isNotEmpty)
            .andExpect(jsonPath("$.error.message").isNotEmpty)
    }

    @Test
    fun `a shipment refused for a cancelled order is reported through the error envelope`() {
        mockMvc.perform(createOrder("O-21")).andExpect(status().isCreated)
        mockMvc.perform(cancel("O-21")).andExpect(status().isOk)

        mockMvc.perform(createShipment("S-21", "O-21"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").exists())
            .andExpect(jsonPath("$.error.code").isNotEmpty)
            .andExpect(jsonPath("$.error.message").isNotEmpty)
    }

    @Test
    fun `error responses do not fall back to the framework default shape`() {
        mockMvc.perform(createOrder("O-22")).andExpect(status().isCreated)
        confirmedShipment("S-22", "O-22")

        // Spring's default error body carries a top-level `path`; the service envelope
        // does not. Asserting its absence catches the case where a handler was added but
        // the failure still escapes through the framework.
        mockMvc.perform(cancel("O-22"))
            .andExpect(jsonPath("$.path").doesNotExist())
            .andExpect(jsonPath("$.timestamp").doesNotExist())
    }
}
