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
 * Evaluator-owned functional suite for BE-005 part 1 — partial fulfilment (AC3, AC5).
 *
 * This file is NOT part of the fixture. The evaluator copies it into the service at
 * evaluation time and removes it afterwards, so correctness never depends on the agent
 * having written the right test itself.
 *
 * The discriminating case is `cancelling a shipment releases its quantity`. An order is
 * filled, one CREATED shipment is cancelled, and the suite then reads fulfilment back AND
 * creates a shipment for the released quantity. An implementation that keeps fulfilment as
 * a stored field on the order, written from the shipment side, passes every earlier case
 * here — create, confirm, deliver, over-allocation — and dies on this one if the cancel
 * site was missed or does not move the status back down. The ticket states the release in
 * one sentence, last.
 *
 * The amendment clause is the second discriminator, added after Gate B on the first
 * ticket: `amending the quantity moves the status in both directions` changes fulfilment
 * with NO shipment event. A stored copy that is kept in step from the shipment side, and a
 * placeholder field that some read path trusts, both answer with the old status; a derived
 * read cannot.
 *
 * Response *shape* is checked separately by BE005ContractTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BE005FulfilmentTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var customers: InMemoryCustomerRepository
    @Autowired lateinit var orders: InMemoryOrderRepository
    @Autowired lateinit var shipments: InMemoryShipmentRepository

    @BeforeEach
    fun reset() {
        customers.clear()
        orders.clear()
        shipments.clear()
        mockMvc.perform(createCustomer("C-1")).andExpect(status().isCreated)
    }

    private fun createCustomer(customerId: String) =
        post("/customers")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"customerId":"$customerId","name":"Ada Lovelace","email":"ada@example.com"}""")

    private fun createOrder(orderId: String, quantity: Int) =
        post("/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"orderId":"$orderId","customerId":"C-1","amount":49.90,"currency":"EUR","quantity":$quantity}""")

    private fun createOrderWithoutQuantity(orderId: String) =
        post("/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"orderId":"$orderId","customerId":"C-1","amount":49.90,"currency":"EUR"}""")

    private fun createShipment(shipmentId: String, orderId: String, quantity: Int) =
        post("/shipments")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"shipmentId":"$shipmentId","orderId":"$orderId","carrier":"DHL","quantity":$quantity}""")

    private fun createShipmentWithoutQuantity(shipmentId: String, orderId: String) =
        post("/shipments")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"shipmentId":"$shipmentId","orderId":"$orderId","carrier":"DHL"}""")

    private fun amend(orderId: String, quantity: Int) =
        put("/orders/$orderId/quantity")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"quantity":$quantity}""")

    private fun confirm(shipmentId: String) = post("/shipments/$shipmentId/confirm")
    private fun deliver(shipmentId: String) = post("/shipments/$shipmentId/deliver")
    private fun cancel(shipmentId: String) = post("/shipments/$shipmentId/cancel")

    private fun expectFulfilment(orderId: String, allocated: Int, delivered: Int, status: String) {
        mockMvc.perform(get("/orders/$orderId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.fulfilment.allocated").value(allocated))
            .andExpect(jsonPath("$.fulfilment.delivered").value(delivered))
            .andExpect(jsonPath("$.fulfilment.status").value(status))
    }

    // ---- quantities -------------------------------------------------------------------

    @Test
    fun `a new order reports its quantity and an unallocated fulfilment`() {
        mockMvc.perform(createOrder("O-1", 5))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.quantity").value(5))

        mockMvc.perform(get("/orders/O-1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.quantity").value(5))
        expectFulfilment("O-1", 0, 0, "UNALLOCATED")
    }

    @Test
    fun `quantity defaults to 1 on an order and on a shipment`() {
        mockMvc.perform(createOrderWithoutQuantity("O-2"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.quantity").value(1))

        mockMvc.perform(createShipmentWithoutQuantity("S-2", "O-2"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.quantity").value(1))

        expectFulfilment("O-2", 1, 0, "FULLY_ALLOCATED")
    }

    @Test
    fun `a non-positive quantity is refused and nothing is stored`() {
        mockMvc.perform(createOrder("O-3", 0)).andExpect(status().isBadRequest)
        mockMvc.perform(get("/orders/O-3")).andExpect(status().isNotFound)

        mockMvc.perform(createOrder("O-3", 2)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-3", "O-3", -1)).andExpect(status().isBadRequest)
        mockMvc.perform(get("/shipments/S-3")).andExpect(status().isNotFound)
        expectFulfilment("O-3", 0, 0, "UNALLOCATED")
    }

    // ---- allocation -------------------------------------------------------------------

    @Test
    fun `shipments allocate the order's quantity`() {
        mockMvc.perform(createOrder("O-4", 5)).andExpect(status().isCreated)

        mockMvc.perform(createShipment("S-4a", "O-4", 2)).andExpect(status().isCreated)
        expectFulfilment("O-4", 2, 0, "PARTIALLY_ALLOCATED")

        mockMvc.perform(createShipment("S-4b", "O-4", 3)).andExpect(status().isCreated)
        expectFulfilment("O-4", 5, 0, "FULLY_ALLOCATED")
    }

    @Test
    fun `over-allocation is refused and nothing changes`() {
        mockMvc.perform(createOrder("O-5", 5)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-5a", "O-5", 3)).andExpect(status().isCreated)

        mockMvc.perform(createShipment("S-5b", "O-5", 3)).andExpect(status().isConflict)

        // The refused shipment was not stored, and the earlier one is untouched.
        mockMvc.perform(get("/shipments/S-5b")).andExpect(status().isNotFound)
        mockMvc.perform(get("/shipments/S-5a")).andExpect(jsonPath("$.status").value("CREATED"))
        expectFulfilment("O-5", 3, 0, "PARTIALLY_ALLOCATED")

        // An exact fill is still accepted.
        mockMvc.perform(createShipment("S-5c", "O-5", 2)).andExpect(status().isCreated)
        expectFulfilment("O-5", 5, 0, "FULLY_ALLOCATED")
    }

    @Test
    fun `a shipment for an unknown order keeps its baseline behaviour`() {
        // The ticket says so explicitly: the allocation rule applies when the order exists,
        // and the baseline test suite depends on unknown orders being accepted.
        mockMvc.perform(createShipment("S-6", "O-nobody", 3)).andExpect(status().isCreated)
    }

    // ---- transitions ------------------------------------------------------------------

    @Test
    fun `confirm, deliver and cancel move a shipment along its lifecycle`() {
        mockMvc.perform(createOrder("O-7", 4)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-7a", "O-7", 2)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-7b", "O-7", 2)).andExpect(status().isCreated)

        mockMvc.perform(confirm("S-7a"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.shipmentId").value("S-7a"))
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
        mockMvc.perform(deliver("S-7a"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("DELIVERED"))
        mockMvc.perform(cancel("S-7b"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELLED"))

        // Persisted, not just returned.
        mockMvc.perform(get("/shipments/S-7a")).andExpect(jsonPath("$.status").value("DELIVERED"))
        mockMvc.perform(get("/shipments/S-7b")).andExpect(jsonPath("$.status").value("CANCELLED"))
    }

    @Test
    fun `a transition from the wrong state is refused and changes nothing`() {
        mockMvc.perform(createOrder("O-8", 6)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-8a", "O-8", 2)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-8b", "O-8", 2)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-8c", "O-8", 2)).andExpect(status().isCreated)
        mockMvc.perform(confirm("S-8b")).andExpect(status().isOk)
        mockMvc.perform(cancel("S-8c")).andExpect(status().isOk)

        // CREATED cannot be delivered.
        mockMvc.perform(deliver("S-8a")).andExpect(status().isConflict)
        mockMvc.perform(get("/shipments/S-8a")).andExpect(jsonPath("$.status").value("CREATED"))

        // CONFIRMED cannot be cancelled or confirmed again.
        mockMvc.perform(cancel("S-8b")).andExpect(status().isConflict)
        mockMvc.perform(confirm("S-8b")).andExpect(status().isConflict)
        mockMvc.perform(get("/shipments/S-8b")).andExpect(jsonPath("$.status").value("CONFIRMED"))

        // CANCELLED is terminal.
        mockMvc.perform(confirm("S-8c")).andExpect(status().isConflict)
        mockMvc.perform(cancel("S-8c")).andExpect(status().isConflict)
        mockMvc.perform(get("/shipments/S-8c")).andExpect(jsonPath("$.status").value("CANCELLED"))
    }

    @Test
    fun `a transition on an unknown shipment returns 404`() {
        mockMvc.perform(confirm("S-missing")).andExpect(status().isNotFound)
        mockMvc.perform(deliver("S-missing")).andExpect(status().isNotFound)
        mockMvc.perform(cancel("S-missing")).andExpect(status().isNotFound)
    }

    // ---- delivered --------------------------------------------------------------------

    @Test
    fun `delivered shipments count toward both allocated and delivered`() {
        mockMvc.perform(createOrder("O-9", 4)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-9a", "O-9", 2)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-9b", "O-9", 2)).andExpect(status().isCreated)
        mockMvc.perform(confirm("S-9a")).andExpect(status().isOk)
        mockMvc.perform(deliver("S-9a")).andExpect(status().isOk)
        mockMvc.perform(confirm("S-9b")).andExpect(status().isOk)

        expectFulfilment("O-9", 4, 2, "FULLY_ALLOCATED")

        mockMvc.perform(deliver("S-9b")).andExpect(status().isOk)
        expectFulfilment("O-9", 4, 4, "DELIVERED")
    }

    // ---- the late clause --------------------------------------------------------------

    @Test
    fun `cancelling a shipment releases its quantity`() {
        mockMvc.perform(createOrder("O-10", 5)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-10a", "O-10", 2)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-10b", "O-10", 3)).andExpect(status().isCreated)
        expectFulfilment("O-10", 5, 0, "FULLY_ALLOCATED")

        mockMvc.perform(cancel("S-10a")).andExpect(status().isOk)

        // The release is visible in the read model: the status moves back DOWN.
        expectFulfilment("O-10", 3, 0, "PARTIALLY_ALLOCATED")

        // And the released quantity can be allocated again.
        mockMvc.perform(createShipment("S-10c", "O-10", 2)).andExpect(status().isCreated)
        expectFulfilment("O-10", 5, 0, "FULLY_ALLOCATED")

        // Cancelling everything that is still CREATED goes all the way back.
        mockMvc.perform(cancel("S-10b")).andExpect(status().isOk)
        mockMvc.perform(cancel("S-10c")).andExpect(status().isOk)
        expectFulfilment("O-10", 0, 0, "UNALLOCATED")
    }

    @Test
    fun `a cancelled shipment never counts, whatever happens after it`() {
        mockMvc.perform(createOrder("O-11", 3)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-11a", "O-11", 3)).andExpect(status().isCreated)
        mockMvc.perform(cancel("S-11a")).andExpect(status().isOk)

        mockMvc.perform(createShipment("S-11b", "O-11", 3)).andExpect(status().isCreated)
        mockMvc.perform(confirm("S-11b")).andExpect(status().isOk)
        mockMvc.perform(deliver("S-11b")).andExpect(status().isOk)

        expectFulfilment("O-11", 3, 3, "DELIVERED")
    }

    // ---- the filter -------------------------------------------------------------------

    @Test
    fun `the list filters by fulfilment status`() {
        mockMvc.perform(createOrder("O-12a", 2)).andExpect(status().isCreated)   // UNALLOCATED
        mockMvc.perform(createOrder("O-12b", 2)).andExpect(status().isCreated)   // PARTIALLY_ALLOCATED
        mockMvc.perform(createOrder("O-12c", 2)).andExpect(status().isCreated)   // PARTIALLY_ALLOCATED
        mockMvc.perform(createOrder("O-12d", 2)).andExpect(status().isCreated)   // FULLY_ALLOCATED
        mockMvc.perform(createShipment("S-12b", "O-12b", 1)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-12c", "O-12c", 1)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-12d", "O-12d", 2)).andExpect(status().isCreated)

        mockMvc.perform(get("/orders").param("fulfilment", "PARTIALLY_ALLOCATED"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].orderId").value("O-12b"))
            .andExpect(jsonPath("$[1].orderId").value("O-12c"))

        mockMvc.perform(get("/orders").param("fulfilment", "UNALLOCATED"))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].orderId").value("O-12a"))

        mockMvc.perform(get("/orders").param("fulfilment", "DELIVERED"))
            .andExpect(jsonPath("$.length()").value(0))

        // Without the parameter the list is unchanged.
        mockMvc.perform(get("/orders")).andExpect(jsonPath("$.length()").value(4))

        // A value that is not a fulfilment status is a bad request, not an empty list.
        mockMvc.perform(get("/orders").param("fulfilment", "SHIPPED")).andExpect(status().isBadRequest)
    }

    @Test
    fun `the list reports fulfilment on every order`() {
        mockMvc.perform(createOrder("O-13", 2)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-13", "O-13", 1)).andExpect(status().isCreated)

        mockMvc.perform(get("/orders"))
            .andExpect(jsonPath("$[0].orderId").value("O-13"))
            .andExpect(jsonPath("$[0].fulfilment.allocated").value(1))
            .andExpect(jsonPath("$[0].fulfilment.status").value("PARTIALLY_ALLOCATED"))
    }

    // ---- the amendment clause ---------------------------------------------------------

    @Test
    fun `amending the quantity moves the status in both directions`() {
        mockMvc.perform(createOrder("O-14", 4)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-14a", "O-14", 2)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-14b", "O-14", 2)).andExpect(status().isCreated)
        expectFulfilment("O-14", 4, 0, "FULLY_ALLOCATED")

        // Raising the quantity moves the status back DOWN with no shipment event at all.
        mockMvc.perform(amend("O-14", 6))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.quantity").value(6))
            .andExpect(jsonPath("$.fulfilment.status").value("PARTIALLY_ALLOCATED"))
        mockMvc.perform(get("/orders/O-14")).andExpect(jsonPath("$.quantity").value(6))
        expectFulfilment("O-14", 4, 0, "PARTIALLY_ALLOCATED")

        // The allocation guard reads the amended quantity.
        mockMvc.perform(createShipment("S-14c", "O-14", 2)).andExpect(status().isCreated)
        expectFulfilment("O-14", 6, 0, "FULLY_ALLOCATED")
        mockMvc.perform(cancel("S-14c")).andExpect(status().isOk)
        expectFulfilment("O-14", 4, 0, "PARTIALLY_ALLOCATED")

        // Lowering to exactly the allocated quantity fills the order again.
        mockMvc.perform(amend("O-14", 4)).andExpect(status().isOk)
        expectFulfilment("O-14", 4, 0, "FULLY_ALLOCATED")

        // And the same in the delivered direction.
        mockMvc.perform(confirm("S-14a")).andExpect(status().isOk)
        mockMvc.perform(deliver("S-14a")).andExpect(status().isOk)
        mockMvc.perform(confirm("S-14b")).andExpect(status().isOk)
        mockMvc.perform(deliver("S-14b")).andExpect(status().isOk)
        expectFulfilment("O-14", 4, 4, "DELIVERED")
        mockMvc.perform(amend("O-14", 5)).andExpect(status().isOk)
        expectFulfilment("O-14", 4, 4, "PARTIALLY_ALLOCATED")
        mockMvc.perform(amend("O-14", 4)).andExpect(status().isOk)
        expectFulfilment("O-14", 4, 4, "DELIVERED")
    }

    @Test
    fun `an amendment below the allocated quantity is refused and nothing changes`() {
        mockMvc.perform(createOrder("O-15", 5)).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-15", "O-15", 3)).andExpect(status().isCreated)

        mockMvc.perform(amend("O-15", 2)).andExpect(status().isConflict)
        mockMvc.perform(get("/orders/O-15")).andExpect(jsonPath("$.quantity").value(5))
        expectFulfilment("O-15", 3, 0, "PARTIALLY_ALLOCATED")

        // Exactly the allocated quantity is allowed.
        mockMvc.perform(amend("O-15", 3)).andExpect(status().isOk)
        expectFulfilment("O-15", 3, 0, "FULLY_ALLOCATED")
    }

    @Test
    fun `a non-positive amendment is refused and nothing changes`() {
        mockMvc.perform(createOrder("O-16", 3)).andExpect(status().isCreated)
        mockMvc.perform(amend("O-16", 0)).andExpect(status().isBadRequest)
        mockMvc.perform(get("/orders/O-16")).andExpect(jsonPath("$.quantity").value(3))
    }

    @Test
    fun `amending an unknown order returns 404`() {
        mockMvc.perform(amend("O-missing", 3)).andExpect(status().isNotFound)
    }
}
