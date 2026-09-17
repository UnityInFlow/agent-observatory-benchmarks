package com.unityinflow.sample

import com.unityinflow.sample.customer.InMemoryCustomerRepository
import com.unityinflow.sample.order.InMemoryOrderRepository
import com.unityinflow.sample.shipment.InMemoryShipmentRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Evaluator-owned contract suite for BE-005 — *how* the new refusals report failure (AC4).
 *
 * Run only after the three functional suites pass: if nothing refuses anything, there is
 * no error response whose shape could be judged.
 *
 * Same trap as BE-002 through BE-004, now in seven new places across three packages, plus
 * the three refusals the quantity amendment adds.
 * `ResponseStatusException`, `ResponseEntity.status(...).build()` and Spring's own binding
 * errors all produce the right status through the framework's default body, whose shape is
 * `{timestamp, status, error, path}` — no `error.code`. This service answers with `ApiError`
 * instead, and the convention is discoverable: every existing failure uses it and the
 * existing tests assert on it. The customer read is the one place the baseline itself
 * breaks the convention; the ticket asks for that to be fixed.
 *
 * Codes that already exist are pinned (`SHIPMENT_NOT_FOUND`, `VALIDATION_FAILED`). The
 * new refusals — unknown customer, over-allocation, wrong transition — pin no name: the
 * fixture has no code for them, so the agent has to add one, and pinning a name would grade
 * a guess at vocabulary rather than adherence to the contract. Field names ARE pinned for
 * the validation failures, because the ticket names the parameters.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BE005ContractTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var customers: InMemoryCustomerRepository
    @Autowired lateinit var orders: InMemoryOrderRepository
    @Autowired lateinit var shipments: InMemoryShipmentRepository

    @BeforeEach
    fun reset() {
        customers.clear()
        orders.clear()
        shipments.clear()
        mockMvc.perform(
            post("/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"customerId":"C-1","name":"Ada Lovelace","email":"ada@example.com"}"""),
        ).andExpect(status().isCreated)
    }

    private fun createOrder(orderId: String, customerId: String = "C-1", quantity: Int = 1) =
        post("/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"orderId":"$orderId","customerId":"$customerId","amount":49.90,"currency":"EUR","quantity":$quantity}""")

    private fun createShipment(shipmentId: String, orderId: String, quantity: Int = 1) =
        post("/shipments")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"shipmentId":"$shipmentId","orderId":"$orderId","carrier":"DHL","quantity":$quantity}""")

    private fun amend(orderId: String, quantity: Int) =
        put("/orders/$orderId/quantity")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"quantity":$quantity}""")

    @Test
    fun `an unknown customer on order creation is reported through the error envelope`() {
        mockMvc.perform(createOrder("O-1", customerId = "C-nobody"))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error").exists())
            .andExpect(jsonPath("$.error.code").isNotEmpty)
            .andExpect(jsonPath("$.error.message").isNotEmpty)
            .andExpect(jsonPath("$.path").doesNotExist())
    }

    @Test
    fun `an unknown customer read is reported through the error envelope`() {
        mockMvc.perform(get("/customers/C-nobody"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").exists())
            .andExpect(jsonPath("$.error.code").isNotEmpty)
            .andExpect(jsonPath("$.error.message").isNotEmpty)
    }

    @Test
    fun `over-allocation is reported through the error envelope`() {
        mockMvc.perform(createOrder("O-2", quantity = 2)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-2a", "O-2", 2)).andExpect(status().isCreated)

        mockMvc.perform(createShipment("S-2b", "O-2", 1))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").exists())
            .andExpect(jsonPath("$.error.code").isNotEmpty)
            .andExpect(jsonPath("$.error.message").isNotEmpty)
            .andExpect(jsonPath("$.timestamp").doesNotExist())
    }

    @Test
    fun `a refused transition is reported through the error envelope`() {
        mockMvc.perform(createOrder("O-3", quantity = 2)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-3", "O-3", 1)).andExpect(status().isCreated)

        mockMvc.perform(post("/shipments/S-3/deliver"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").exists())
            .andExpect(jsonPath("$.error.code").isNotEmpty)
            .andExpect(jsonPath("$.error.message").isNotEmpty)
    }

    @Test
    fun `a transition on an unknown shipment uses the existing not-found code`() {
        mockMvc.perform(post("/shipments/S-missing/confirm"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("SHIPMENT_NOT_FOUND"))
            .andExpect(jsonPath("$.error.message").isNotEmpty)
    }

    @Test
    fun `a non-positive quantity is a validation failure naming the field`() {
        mockMvc.perform(createOrder("O-4", quantity = 0))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.error.fields[0].field").value("quantity"))

        mockMvc.perform(createOrder("O-4", quantity = 3)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-4", "O-4", 0))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.error.fields[0].field").value("quantity"))
    }

    @Test
    fun `a bad page parameter is a validation failure naming the parameter`() {
        mockMvc.perform(get("/customers").param("limit", "0"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.error.fields[0].field").value("limit"))

        mockMvc.perform(get("/shipments").param("offset", "-1"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.error.fields[0].field").value("offset"))
    }

    @Test
    fun `an unknown fulfilment filter value is a validation failure naming the parameter`() {
        mockMvc.perform(get("/orders").param("fulfilment", "SHIPPED"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.error.fields[0].field").value("fulfilment"))
            .andExpect(jsonPath("$.path").doesNotExist())
    }

    @Test
    fun `an amendment below the allocated quantity is reported through the error envelope`() {
        mockMvc.perform(createOrder("O-5", quantity = 2)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-5", "O-5", 2)).andExpect(status().isCreated)

        mockMvc.perform(amend("O-5", 1))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").exists())
            .andExpect(jsonPath("$.error.code").isNotEmpty)
            .andExpect(jsonPath("$.error.message").isNotEmpty)
            .andExpect(jsonPath("$.timestamp").doesNotExist())
    }

    @Test
    fun `a non-positive amendment is a validation failure naming the field`() {
        mockMvc.perform(createOrder("O-6", quantity = 2)).andExpect(status().isCreated)
        mockMvc.perform(amend("O-6", 0))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.error.fields[0].field").value("quantity"))
    }

    @Test
    fun `an amendment of an unknown order uses the existing not-found code`() {
        mockMvc.perform(amend("O-missing", 3))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"))
            .andExpect(jsonPath("$.error.message").isNotEmpty)
    }
}
