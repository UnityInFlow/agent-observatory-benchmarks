package com.unityinflow.sample.order

import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * Evaluator-owned contract suite for BE-002 — *how* the endpoint reports the failure (AC4).
 *
 * The service answers every error with one envelope:
 *
 * ```json
 * { "error": { "code": "...", "message": "...", "fields": [ ... ] } }
 * ```
 *
 * A submission that returns HTTP 400 through Spring's default error handling breaks that
 * contract for every client that switches on `error.code`, even though the status code
 * looks right. Splitting this suite out of the functional one is what lets the evaluator
 * report "correct behaviour, wrong contract" as its own failure class.
 *
 * Deliberately *not* asserted: which code string the agent chose. The convention is the
 * envelope, not a magic word; requiring a specific unguessable value would turn the
 * benchmark into a lottery.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BE002ContractTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var repository: InMemoryOrderRepository

    @BeforeEach
    fun reset() = repository.clear()

    private fun postOrder(orderId: String, amount: String): Pair<Int, String> {
        val response = mockMvc.perform(
            post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"orderId":"$orderId","customerId":"C-1","amount":$amount,"currency":"EUR"}""",
                ),
        ).andReturn().response
        return response.status to response.contentAsString
    }

    /** Reads a path out of a body that may be empty or not JSON at all, without throwing. */
    private fun read(body: String, path: String): String? =
        runCatching { JsonPath.read<Any?>(body, path)?.toString() }.getOrNull()

    private fun assertUsesErrorEnvelope(status: Int, body: String, situation: String) {
        check(body.isNotBlank()) {
            "$situation returned HTTP $status with an empty body. This API answers errors " +
                "with the ApiError envelope, e.g. {\"error\":{\"code\":...,\"message\":...}}."
        }
        val code = read(body, "$.error.code")
        val message = read(body, "$.error.message")
        check(!code.isNullOrBlank() && !message.isNullOrBlank()) {
            "$situation returned HTTP $status but not in the ApiError envelope. " +
                "Expected {\"error\":{\"code\":...,\"message\":...}}, got: $body"
        }
    }

    /** AC4 — the new rejection must speak the same error language as the rest of the API. */
    @Test
    fun `the rejection uses the ApiError envelope`() {
        val (status, body) = postOrder("O-ZERO", "0")
        check(status == 400) { "expected HTTP 400 for a zero amount, got $status (see BE002FunctionalTest)" }
        assertUsesErrorEnvelope(status, body, "a zero amount")
    }

    /** AC4 — and it must do so consistently, not only for the one case in the ticket. */
    @Test
    fun `the rejection uses the ApiError envelope for a negative amount too`() {
        val (status, body) = postOrder("O-NEG", "-12.50")
        check(status == 400) { "expected HTTP 400 for a negative amount, got $status (see BE002FunctionalTest)" }
        assertUsesErrorEnvelope(status, body, "a negative amount")
    }

    /** AC4 — the error contract that already existed must still hold. */
    @Test
    fun `the existing 404 envelope is unchanged`() {
        val response = mockMvc.perform(get("/orders/does-not-exist")).andReturn().response
        check(response.status == 404) { "expected HTTP 404 for an unknown order, got ${response.status}" }
        val body = response.contentAsString
        check(read(body, "$.error.code") == "ORDER_NOT_FOUND") {
            "the existing 404 error contract was broken. Expected error.code=ORDER_NOT_FOUND, got: $body"
        }
    }

    /** AC4 — as must the conflict contract. */
    @Test
    fun `the existing 409 envelope is unchanged`() {
        check(postOrder("O-DUP", "10.00").first == 201) { "could not create the order the conflict test needs" }
        val (status, body) = postOrder("O-DUP", "10.00")
        check(status == 409) { "expected HTTP 409 for a duplicate order id, got $status" }
        check(read(body, "$.error.code") == "ORDER_ALREADY_EXISTS") {
            "the existing 409 error contract was broken. Expected error.code=ORDER_ALREADY_EXISTS, got: $body"
        }
    }
}
