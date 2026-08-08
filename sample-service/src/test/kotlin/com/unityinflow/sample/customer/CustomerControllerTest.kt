package com.unityinflow.sample.customer

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Baseline behaviour of the fixture. BE-001 must not break any of these.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var repository: InMemoryCustomerRepository

    @BeforeEach
    fun reset() = repository.clear()

    @Test
    fun `creates a customer and returns 201`() {
        mockMvc.perform(
            post("/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"customerId":"C-1","name":"Ada Lovelace","email":"ada@example.com"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.customerId").value("C-1"))
    }

    @Test
    fun `reads back a created customer`() {
        mockMvc.perform(
            post("/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"customerId":"C-2","name":"Grace Hopper","email":"grace@example.com"}"""),
        ).andExpect(status().isCreated)

        mockMvc.perform(get("/customers/C-2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Grace Hopper"))
    }

    @Test
    fun `returns 404 for an unknown customer`() {
        mockMvc.perform(get("/customers/does-not-exist"))
            .andExpect(status().isNotFound)
    }
}
