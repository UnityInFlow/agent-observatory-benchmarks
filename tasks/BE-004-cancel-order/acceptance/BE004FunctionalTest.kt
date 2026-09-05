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
 * Evaluator-owned functional suite for BE-004 — *what* the endpoint does (AC3, AC5).
 *
 * This file is NOT part of the fixture. The evaluator copies it into the service at
 * evaluation time and removes it afterwards, so correctness never depends on the agent
 * having written the right test itself.
 *
 * The discriminating case is `a cancel blocked by a confirmed shipment changes nothing`.
 * The order's shipments are set up so that a CREATED one sorts before the CONFIRMED one:
 * an implementation that cancels as it iterates and throws when it reaches the CONFIRMED
 * shipment returns the right status with the wrong state behind it. That is a requirement
 * failure — the ticket says "nothing changes" — not a style disagreement, and it separates
 * a submission that read the shipment module before writing from one that did not.
 *
 * The second discriminator is `a cancelled order refuses new shipments`: a consequence the
 * ticket states in one line, in the other feature's controller.
 *
 * Response *shape* is checked separately by BE004ContractTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BE004FunctionalTest {

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
    fun `cancelling an active order returns 200 and reports CANCELLED`() {
        mockMvc.perform(createOrder("O-1")).andExpect(status().isCreated)

        mockMvc.perform(cancel("O-1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.orderId").value("O-1"))
            .andExpect(jsonPath("$.status").value("CANCELLED"))
    }

    @Test
    fun `the cancelled status is persisted, not just returned`() {
        mockMvc.perform(createOrder("O-2")).andExpect(status().isCreated)
        mockMvc.perform(cancel("O-2")).andExpect(status().isOk)

        mockMvc.perform(get("/orders/O-2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELLED"))
    }

    @Test
    fun `cancelling an order cancels its CREATED shipments`() {
        mockMvc.perform(createOrder("O-3")).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-3a", "O-3")).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-3b", "O-3")).andExpect(status().isCreated)

        mockMvc.perform(cancel("O-3")).andExpect(status().isOk)

        mockMvc.perform(get("/shipments/S-3a")).andExpect(jsonPath("$.status").value("CANCELLED"))
        mockMvc.perform(get("/shipments/S-3b")).andExpect(jsonPath("$.status").value("CANCELLED"))
    }

    @Test
    fun `cancelling leaves other orders' shipments alone`() {
        mockMvc.perform(createOrder("O-4")).andExpect(status().isCreated)
        mockMvc.perform(createOrder("O-5")).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-4", "O-4")).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-5", "O-5")).andExpect(status().isCreated)

        mockMvc.perform(cancel("O-4")).andExpect(status().isOk)

        mockMvc.perform(get("/shipments/S-5")).andExpect(jsonPath("$.status").value("CREATED"))
        mockMvc.perform(get("/orders/O-5")).andExpect(jsonPath("$.status").value("ACTIVE"))
    }

    @Test
    fun `an already cancelled shipment does not block the cancel`() {
        mockMvc.perform(createOrder("O-6")).andExpect(status().isCreated)
        shipments.save(Shipment("S-6", "O-6", "DHL", ShipmentStatus.CANCELLED))

        mockMvc.perform(cancel("O-6"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELLED"))
    }

    @Test
    fun `cancelling twice is idempotent`() {
        mockMvc.perform(createOrder("O-7")).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-7", "O-7")).andExpect(status().isCreated)

        mockMvc.perform(cancel("O-7"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELLED"))

        // The repeat is not an error. Same status code, same resulting state.
        mockMvc.perform(cancel("O-7"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.orderId").value("O-7"))
            .andExpect(jsonPath("$.status").value("CANCELLED"))

        mockMvc.perform(get("/shipments"))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].status").value("CANCELLED"))
    }

    @Test
    fun `a cancel blocked by a confirmed shipment changes nothing`() {
        mockMvc.perform(createOrder("O-8")).andExpect(status().isCreated)
        // The CREATED shipment sorts first. An implementation that cancels while it
        // iterates will have cancelled S-8a before it meets S-8b.
        mockMvc.perform(createShipment("S-8a", "O-8")).andExpect(status().isCreated)
        confirmedShipment("S-8b", "O-8")

        mockMvc.perform(cancel("O-8")).andExpect(status().isConflict)

        // All-or-nothing: the order and every shipment are exactly as they were.
        mockMvc.perform(get("/orders/O-8")).andExpect(jsonPath("$.status").value("ACTIVE"))
        mockMvc.perform(get("/shipments/S-8a")).andExpect(jsonPath("$.status").value("CREATED"))
        mockMvc.perform(get("/shipments/S-8b")).andExpect(jsonPath("$.status").value("CONFIRMED"))
    }

    @Test
    fun `cancelling an unknown order returns 404`() {
        mockMvc.perform(cancel("O-missing")).andExpect(status().isNotFound)
    }

    @Test
    fun `a cancelled order refuses new shipments`() {
        mockMvc.perform(createOrder("O-9")).andExpect(status().isCreated)
        mockMvc.perform(cancel("O-9")).andExpect(status().isOk)

        mockMvc.perform(createShipment("S-9", "O-9")).andExpect(status().isConflict)

        // And the refused shipment was not stored.
        mockMvc.perform(get("/shipments/S-9")).andExpect(status().isNotFound)
    }

    @Test
    fun `an active order still takes new shipments`() {
        mockMvc.perform(createOrder("O-10")).andExpect(status().isCreated)

        mockMvc.perform(createShipment("S-10", "O-10"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("CREATED"))
    }

    @Test
    fun `a shipment for an unknown order keeps its baseline behaviour`() {
        // The ticket says so explicitly: this is not the place to add an order-existence
        // check, and the baseline test suite depends on it.
        mockMvc.perform(createShipment("S-11", "O-nobody")).andExpect(status().isCreated)
    }

    @Test
    fun `creating an order still works and reports ACTIVE`() {
        mockMvc.perform(createOrder("O-12"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("ACTIVE"))
    }
}
