package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.net.URI
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The HTTP contract for `/api/v1/workspaces/{workspaceId}/operations` (task 0.9).
 *
 * Runs the real filter chain against a real Postgres: the parts most likely to break here are
 * routing, serialisation and security, none of which a standalone controller test exercises.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class OperationsRestControllerTest(
	@Autowired private val mvc: MockMvc,
	@Autowired private val jdbc: JdbcClient,
) {

	@BeforeEach
	fun clean() {
		jdbc.sql("DELETE FROM t_operations").update()
		jdbc.sql("DELETE FROM t_events").update()
	}

	@Test
	fun `creates an operation and returns its id`() {
		mvc.perform(createRequest())
			.andExpect(status().isCreated)
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.id").exists())
	}

	@Test
	fun `points the Location header at the created operation`() {
		val response = mvc.perform(createRequest()).andExpect(status().isCreated).andReturn().response
		val id = idOf(response.contentAsString)

		response.getHeader("Location").let { location ->
			assertEquals("$OPERATIONS_PATH/$id", URI.create(location!!).path)
		}

		// The header is only useful if it actually resolves — follow it.
		mvc.perform(get(URI.create(response.getHeader("Location")!!).path).with(user(USER)))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.id").value(id))
	}

	@Test
	fun `persists what the request carried`() {
		val id = idOf(mvc.perform(createRequest()).andReturn().response.contentAsString)

		val stored = jdbc.sql("SELECT amount, occurred_at, workspace_id FROM t_operations WHERE id = :id")
			.param("id", UUID.fromString(id))
			.query { rs, _ ->
				Triple(
					rs.getBigDecimal("amount"),
					rs.getObject("occurred_at", LocalDateTime::class.java),
					rs.getObject("workspace_id", UUID::class.java),
				)
			}
			.single()

		assertEquals(BigDecimal("-1234.5600"), stored.first)
		assertEquals(OCCURRED_AT, stored.second)
		// The workspace comes from the path, never from the body (§10.1).
		assertEquals(WORKSPACE_ID, stored.third)
	}

	@Test
	fun `returns the operation with the fields the mapper exposes`() {
		val id = idOf(mvc.perform(createRequest()).andReturn().response.contentAsString)

		mvc.perform(get("$OPERATIONS_PATH/$id").with(user(USER)))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.id").value(id))
			.andExpect(jsonPath("$.amount").value(-1234.5600))
			.andExpect(jsonPath("$.occurredAt").value("2026-03-15T14:30:00"))
			.andExpect(jsonPath("$.recordedAt").exists())
			// workspaceId is in the path already; the response must not repeat it.
			.andExpect(jsonPath("$.workspaceId").doesNotExist())
	}

	@Test
	fun `accepts a back-dated operation`() {
		val backDated = "2020-01-01T08:00:00"
		val id = idOf(
			mvc.perform(createRequest(occurredAt = backDated)).andReturn().response.contentAsString
		)

		// §6.1: retrospective entry is the norm, and the API must not treat it as an error.
		mvc.perform(get("$OPERATIONS_PATH/$id").with(user(USER)))
			.andExpect(jsonPath("$.occurredAt").value(backDated))
	}

	@Test
	fun `accepts a positive amount`() {
		val id = idOf(
			mvc.perform(createRequest(amount = "250.0000")).andReturn().response.contentAsString
		)

		mvc.perform(get("$OPERATIONS_PATH/$id").with(user(USER)))
			.andExpect(jsonPath("$.amount").value(250.0000))
	}

	@Test
	fun `rejects a malformed body`() {
		mvc.perform(
			post(OPERATIONS_PATH).with(user(USER)).with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""{"amount":"not-a-number","occurredAt":"2026-03-15T14:30:00"}""")
		).andExpect(status().isBadRequest)
	}

	@Test
	fun `rejects an unauthenticated create`() {
		mvc.perform(
			post(OPERATIONS_PATH).contentType(MediaType.APPLICATION_JSON).content(body())
		).andExpect(status().isForbidden)

		assertEquals(0, count(), "nothing may be written for an unauthenticated caller")
	}

	@Test
	fun `rejects an unauthenticated read`() {
		mvc.perform(get("$OPERATIONS_PATH/${UUID.randomUUID()}"))
			.andExpect(status().isForbidden)
	}

	private fun createRequest(
		amount: String = "-1234.5600",
		occurredAt: String = "2026-03-15T14:30:00",
	) = post(OPERATIONS_PATH)
		.with(user(USER))
		.with(csrf())
		.contentType(MediaType.APPLICATION_JSON)
		.content(body(amount, occurredAt))

	private fun body(amount: String = "-1234.5600", occurredAt: String = "2026-03-15T14:30:00") =
		"""{"amount":"$amount","occurredAt":"$occurredAt"}"""

	private fun idOf(json: String) = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(json)!!.groupValues[1]

	private fun count() = jdbc.sql("SELECT count(*) FROM t_operations WHERE workspace_id = :id")
		.param("id", WORKSPACE_ID)
		.query(Int::class.java).single()

	private companion object {
		const val USER = "tester"
		val WORKSPACE_ID: UUID = UUID.fromString("0199a1c2-3d4e-7f80-8123-000000000001")
		val OCCURRED_AT: LocalDateTime = LocalDateTime.parse("2026-03-15T14:30:00")
		const val OPERATIONS_PATH = "/api/v1/workspaces/0199a1c2-3d4e-7f80-8123-000000000001/operations"
	}
}
