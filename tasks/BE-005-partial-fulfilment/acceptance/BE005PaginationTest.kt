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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Evaluator-owned functional suite for BE-005 part 3 — pagination on every list (AC3).
 *
 * The cross-cutting part of the epic: the same `limit` / `offset` contract on three
 * endpoints in three packages, with the array body unchanged so every unpaged caller —
 * including the baseline tests — keeps working, and the total in an `X-Total-Count` header.
 * The last case composes it with part 1's filter: the total is the filtered total.
 *
 * Response *shape* of the refusals is checked separately by BE005ContractTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BE005PaginationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var customers: InMemoryCustomerRepository
    @Autowired lateinit var orders: InMemoryOrderRepository
    @Autowired lateinit var shipments: InMemoryShipmentRepository

    @BeforeEach
    fun reset() {
        customers.clear()
        orders.clear()
        shipments.clear()
    }

    private fun createCustomer(customerId: String) =
        post("/customers")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"customerId":"$customerId","name":"Ada Lovelace","email":"ada@example.com"}""")

    private fun createOrder(orderId: String, quantity: Int = 1) =
        post("/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"orderId":"$orderId","customerId":"C-1","amount":49.90,"currency":"EUR","quantity":$quantity}""")

    private fun createShipment(shipmentId: String, orderId: String, quantity: Int = 1) =
        post("/shipments")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"shipmentId":"$shipmentId","orderId":"$orderId","carrier":"DHL","quantity":$quantity}""")

    private fun fiveCustomers() {
        for (i in 1..5) mockMvc.perform(createCustomer("C-$i")).andExpect(status().isCreated)
    }

    @Test
    fun `an unpaged list returns everything, in id order, with the total in a header`() {
        fiveCustomers()

        mockMvc.perform(get("/customers"))
            .andExpect(status().isOk)
            .andExpect(header().string("X-Total-Count", "5"))
            .andExpect(jsonPath("$.length()").value(5))
            .andExpect(jsonPath("$[0].customerId").value("C-1"))
            .andExpect(jsonPath("$[4].customerId").value("C-5"))
    }

    @Test
    fun `limit and offset slice the list and the total is unchanged`() {
        fiveCustomers()

        mockMvc.perform(get("/customers").param("limit", "2").param("offset", "2"))
            .andExpect(status().isOk)
            .andExpect(header().string("X-Total-Count", "5"))
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].customerId").value("C-3"))
            .andExpect(jsonPath("$[1].customerId").value("C-4"))

        mockMvc.perform(get("/customers").param("limit", "2"))
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].customerId").value("C-1"))

        mockMvc.perform(get("/customers").param("offset", "4"))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].customerId").value("C-5"))
    }

    @Test
    fun `an offset past the end is an empty page, not an error`() {
        fiveCustomers()

        mockMvc.perform(get("/customers").param("limit", "2").param("offset", "9"))
            .andExpect(status().isOk)
            .andExpect(header().string("X-Total-Count", "5"))
            .andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `a limit outside 1 to 100 or a negative offset is refused`() {
        fiveCustomers()

        mockMvc.perform(get("/customers").param("limit", "0")).andExpect(status().isBadRequest)
        mockMvc.perform(get("/customers").param("limit", "101")).andExpect(status().isBadRequest)
        mockMvc.perform(get("/customers").param("offset", "-1")).andExpect(status().isBadRequest)
        mockMvc.perform(get("/customers").param("limit", "100")).andExpect(status().isOk)
    }

    @Test
    fun `orders page the same way`() {
        mockMvc.perform(createCustomer("C-1")).andExpect(status().isCreated)
        for (i in 1..3) mockMvc.perform(createOrder("O-$i")).andExpect(status().isCreated)

        mockMvc.perform(get("/orders").param("limit", "1").param("offset", "1"))
            .andExpect(status().isOk)
            .andExpect(header().string("X-Total-Count", "3"))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].orderId").value("O-2"))

        mockMvc.perform(get("/orders").param("limit", "0")).andExpect(status().isBadRequest)
    }

    @Test
    fun `shipments page the same way`() {
        mockMvc.perform(createCustomer("C-1")).andExpect(status().isCreated)
        mockMvc.perform(createOrder("O-1", 9)).andExpect(status().isCreated)
        for (i in 1..3) mockMvc.perform(createShipment("S-$i", "O-1")).andExpect(status().isCreated)

        mockMvc.perform(get("/shipments").param("limit", "1").param("offset", "1"))
            .andExpect(status().isOk)
            .andExpect(header().string("X-Total-Count", "3"))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].shipmentId").value("S-2"))

        mockMvc.perform(get("/shipments").param("offset", "-1")).andExpect(status().isBadRequest)
    }

    @Test
    fun `the fulfilment filter and the page compose, and the total is the filtered total`() {
        mockMvc.perform(createCustomer("C-1")).andExpect(status().isCreated)
        mockMvc.perform(createOrder("O-1", 2)).andExpect(status().isCreated)   // PARTIALLY_ALLOCATED
        mockMvc.perform(createOrder("O-2", 2)).andExpect(status().isCreated)   // UNALLOCATED
        mockMvc.perform(createOrder("O-3", 2)).andExpect(status().isCreated)   // PARTIALLY_ALLOCATED
        mockMvc.perform(createShipment("S-1", "O-1")).andExpect(status().isCreated)
        mockMvc.perform(createShipment("S-3", "O-3")).andExpect(status().isCreated)

        mockMvc.perform(get("/orders").param("fulfilment", "PARTIALLY_ALLOCATED").param("limit", "1"))
            .andExpect(status().isOk)
            .andExpect(header().string("X-Total-Count", "2"))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].orderId").value("O-1"))

        mockMvc.perform(get("/orders").param("fulfilment", "PARTIALLY_ALLOCATED").param("limit", "1").param("offset", "1"))
            .andExpect(header().string("X-Total-Count", "2"))
            .andExpect(jsonPath("$[0].orderId").value("O-3"))
    }
}
