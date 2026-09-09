package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.dao.UsersDAO
import com.github.melancholic.fintrace.core.dao.WorkspaceDAO
import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.CategoryProjectionDAO
import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.math.BigDecimal
import java.net.URI
import java.time.LocalDateTime
import java.util.*
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
	@Autowired private val workspaceDAO: WorkspaceDAO,
	@Autowired private val usersDAO: UsersDAO,
    @Autowired private val accountDAO: AccountProjectionDAO,
    @Autowired private val categoryDAO: CategoryProjectionDAO,
) {

	private lateinit var workspaceId: UUID
    private lateinit var accountId: UUID
    private lateinit var categoryId: UUID

	/** Built per test: the workspace is created through the DAO, so its id is minted, not fixed. */
	private val operationsPath get() = "/api/v1/workspaces/$workspaceId/operations"

	@BeforeEach
	fun clean() {
		TestWorkspaces.reset(jdbc)
		workspaceId = TestWorkspaces.create(workspaceDAO, usersDAO)
        accountId = TestWorkspaces.seedAccount(accountDAO, workspaceId)
        categoryId = TestWorkspaces.seedCategory(categoryDAO, workspaceId)
	}

	@Test
    fun `creates an operation and returns its state`() {
		mvc.perform(createRequest())
			.andExpect(status().isCreated)
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.amount").value(1234.5600))
            .andExpect(jsonPath("$.occurredAt").value("2026-03-15T14:30:00"))
            .andExpect(jsonPath("$.recordedAt").exists())
	}

	@Test
	fun `points the Location header at the created operation`() {
		val response = mvc.perform(createRequest()).andExpect(status().isCreated).andReturn().response
		val id = idOf(response.contentAsString)

		response.getHeader("Location").let { location ->
			assertEquals("${operationsPath}/$id", URI.create(location!!).path)
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
		assertEquals(workspaceId, stored.third)
	}

	@Test
	fun `returns the operation with the fields the mapper exposes`() {
		val id = idOf(mvc.perform(createRequest()).andReturn().response.contentAsString)

		mvc.perform(get("${operationsPath}/$id").with(user(USER)))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.amount").value(1234.5600))
			.andExpect(jsonPath("$.occurredAt").value("2026-03-15T14:30:00"))
			.andExpect(jsonPath("$.recordedAt").exists())
            // Carried even though the path already names it, so a response object stays meaningful
            // once it is held apart from the request that fetched it — as AccountResponse and
            // CategoryResponse already do.
            .andExpect(jsonPath("$.workspaceId").value(workspaceId.toString()))
            .andExpect(jsonPath("$.accountId").value(accountId.toString()))
            .andExpect(jsonPath("$.categoryId").value(categoryId.toString()))
            .andExpect(jsonPath("$.kind").value("EXPENSE"))
            .andExpect(jsonPath("$.transferId").doesNotExist())
            .andExpect(jsonPath("$.counterpartId").doesNotExist())
            .andExpect(jsonPath("$.externalRef").doesNotExist())
	}

	@Test
	fun `accepts a back-dated operation`() {
		val backDated = "2020-01-01T08:00:00"
		val id = idOf(
			mvc.perform(createRequest(occurredAt = backDated)).andReturn().response.contentAsString
		)

		// §6.1: retrospective entry is the norm, and the API must not treat it as an error.
		mvc.perform(get("${operationsPath}/$id").with(user(USER)))
			.andExpect(jsonPath("$.occurredAt").value(backDated))
	}

	@Test
    fun `answers with the magnitude it was given, not the stored sign`() {
		val id = idOf(
			mvc.perform(createRequest(amount = "250.0000")).andReturn().response.contentAsString
		)

        // The sign is an internal convention (§4.13): stored negative so a balance is SUM(amount)
        // with no CASE, but absolute on the wire in both directions — otherwise a client that
        // reads an expense and writes it straight back gets a 400 for a body it did not author.
		mvc.perform(get("${operationsPath}/$id").with(user(USER)))
			.andExpect(jsonPath("$.amount").value(250.0000))
	}

    @Test
    fun `stores an income positive and an expense negative`() {
        // An income needs a category in the income branch: an operation's kind must agree with
        // its category's, or the category would rewrite what the operation means.
        val incomeCategory = TestWorkspaces.seedCategory(categoryDAO, workspaceId, CategoryKind.INCOME)
        val expense = idOf(mvc.perform(createRequest(amount = "250.0000")).andReturn().response.contentAsString)
        val income = idOf(
            mvc.perform(
                post(operationsPath).with(user(USER)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        body(amount = "250.0000")
                            .replace("\"EXPENSE\"", "\"INCOME\"")
                            .replace(categoryId.toString(), incomeCategory.toString())
                    )
            ).andExpect(status().isCreated).andReturn().response.contentAsString
        )

        // Read from the table, not the response: the response is unsigned by design, so only the
        // stored value shows that a balance is SUM(amount) with no CASE (§4.13).
        assertEquals(BigDecimal("-250.0000"), storedAmount(UUID.fromString(expense)))
        assertEquals(BigDecimal("250.0000"), storedAmount(UUID.fromString(income)))
    }

    @Test
    fun `rejects a negative amount`() {
        mvc.perform(createRequest(amount = "-250.0000"))
            .andExpect(status().isBadRequest)

        assertEquals(0, count(), "a rejected command writes nothing")
    }

    @Test
    fun `refuses to write a transfer leg through the operations endpoint`() {
        // A transfer is a pair, and the pair is the invariant (§4.5): a leg created here would
        // have no counterpart and no transfer_id, which is a half-transfer no rebuild can repair.
        // /transfers is the only way in (1.20).
        mvc.perform(
            post(operationsPath).with(user(USER)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body().replace("\"EXPENSE\"", "\"TRANSFER\""))
        ).andExpect(status().isConflict)

        assertEquals(0, count(), "a rejected command writes nothing")
        assertEquals(0, eventCount())
    }

	@Test
	fun `rejects a malformed body`() {
		mvc.perform(
			post(operationsPath).with(user(USER)).with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""{"amount":"not-a-number","occurredAt":"2026-03-15T14:30:00"}""")
		).andExpect(status().isBadRequest)
	}

	@Test
    fun `revises an operation and answers with its new state`() {
		val id = createdId()

        // The response is the row that was written, so a client needs no second request (§10.0).
		mvc.perform(reviseRequest(id, amount = "42.0000", occurredAt = "2021-05-05T10:00:00"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(id.toString()))
            .andExpect(jsonPath("$.amount").value(42.0000))
            .andExpect(jsonPath("$.occurredAt").value("2021-05-05T10:00:00"))

		mvc.perform(get("${operationsPath}/$id").with(user(USER)))
			.andExpect(jsonPath("$.id").value(id.toString()))
			.andExpect(jsonPath("$.amount").value(42.0000))
			.andExpect(jsonPath("$.occurredAt").value("2021-05-05T10:00:00"))
	}

	@Test
	fun `a revision replaces rather than adds`() {
		val id = createdId()

        mvc.perform(reviseRequest(id)).andExpect(status().isOk)

		// The projection holds current state only; the history lives in the event log.
		assertEquals(1, count())
		assertEquals(2, eventCount())
	}

	@Test
	fun `rejects a revision of an unknown operation`() {
		mvc.perform(reviseRequest(UUID.randomUUID()))
			.andExpect(status().isNotFound)

		// Without the existence check the upsert would insert a row under a client-supplied id.
		assertEquals(0, count())
		assertEquals(0, eventCount())
	}

	@Test
	fun `rejects a revision of an operation belonging to another workspace`() {
		val id = createdId()
		val otherWorkspace = "/api/v1/workspaces/${UUID.randomUUID()}/operations"

		mvc.perform(
			put("$otherWorkspace/$id").with(user(USER)).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(body())
		).andExpect(status().isNotFound)

		// Indistinguishable from "no such operation" on purpose — an id you cannot reach and one
		// that does not exist must give the same answer.
		assertEquals(1, eventCount())
	}

	@Test
	fun `rejects a revision dated in the future`() {
		val id = createdId()

		mvc.perform(reviseRequest(id, occurredAt = "2099-01-01T00:00:00"))
			.andExpect(status().isBadRequest)

		assertEquals(1, eventCount())
	}

	@Test
	fun `rejects a malformed revision body`() {
		val id = createdId()

		mvc.perform(
			put("${operationsPath}/$id").with(user(USER)).with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""{"amount":"not-a-number","occurredAt":"2026-03-15T14:30:00"}""")
		).andExpect(status().isBadRequest)
	}

	@Test
	fun `rejects an unauthenticated revision`() {
		val id = createdId()

		mvc.perform(
			put("${operationsPath}/$id").contentType(MediaType.APPLICATION_JSON).content(body())
		).andExpect(status().isForbidden)

		assertEquals(1, eventCount(), "nothing may be written for an unauthenticated caller")
	}

	@Test
	fun `cancels an operation and answers with no content`() {
		val id = createdId()

		mvc.perform(cancelRequest(id))
			.andExpect(status().isNoContent)
			.andExpect(content().string(""))

		// §10.2: a cancelled operation disappears entirely rather than being flagged.
		mvc.perform(get("${operationsPath}/$id").with(user(USER)))
			.andExpect(status().isNotFound)
		assertEquals(0, count())
		assertEquals(2, eventCount(), "the cancellation is recorded even though the row is gone")
	}

	@Test
	fun `rejects cancelling the same operation twice`() {
		val id = createdId()

		mvc.perform(cancelRequest(id)).andExpect(status().isNoContent)
		mvc.perform(cancelRequest(id)).andExpect(status().isNotFound)

		assertEquals(2, eventCount(), "the second attempt writes nothing")
	}

	@Test
	fun `rejects cancelling an unknown operation`() {
		mvc.perform(cancelRequest(UUID.randomUUID()))
			.andExpect(status().isNotFound)

		assertEquals(0, eventCount())
	}

	@Test
	fun `rejects an unauthenticated cancel`() {
		val id = createdId()

		mvc.perform(delete("${operationsPath}/$id"))
			.andExpect(status().isForbidden)

		assertEquals(1, count(), "nothing may be removed for an unauthenticated caller")
	}

	@Test
	fun `rejects an unauthenticated create`() {
		mvc.perform(
			post(operationsPath).contentType(MediaType.APPLICATION_JSON).content(body())
		).andExpect(status().isForbidden)

		assertEquals(0, count(), "nothing may be written for an unauthenticated caller")
	}

	@Test
	fun `rejects an unauthenticated read`() {
		mvc.perform(get("${operationsPath}/${UUID.randomUUID()}"))
			.andExpect(status().isForbidden)
	}

	private fun createRequest(
        amount: String = "1234.5600",
		occurredAt: String = "2026-03-15T14:30:00",
	) = post(operationsPath)
		.with(user(USER))
		.with(csrf())
		.contentType(MediaType.APPLICATION_JSON)
		.content(body(amount, occurredAt))

	private fun reviseRequest(
		operationId: UUID,
        amount: String = "1234.5600",
		occurredAt: String = "2026-03-15T14:30:00",
	) = put("${operationsPath}/$operationId")
		.with(user(USER))
		.with(csrf())
		.contentType(MediaType.APPLICATION_JSON)
		.content(body(amount, occurredAt))

	private fun cancelRequest(operationId: UUID) = delete("${operationsPath}/$operationId")
		.with(user(USER))
		.with(csrf())

	private fun createdId(): UUID = UUID.fromString(
		idOf(mvc.perform(createRequest()).andExpect(status().isCreated).andReturn().response.contentAsString)
	)

	private fun eventCount() = jdbc.sql("SELECT count(*) FROM t_events")
		.query(Int::class.java).single()

    private fun storedAmount(id: UUID) = jdbc.sql("SELECT amount FROM t_operations WHERE id = :id")
        .param("id", id)
        .query(BigDecimal::class.java).single()

    /** The request carries a magnitude plus a kind; the sign is Core's business (§4.13). */
    private fun body(amount: String = "1234.5600", occurredAt: String = "2026-03-15T14:30:00") =
        """
		{"amount":"$amount","occurredAt":"$occurredAt","kind":"EXPENSE",
		 "accountId":"$accountId","categoryId":"$categoryId","comment":null}
		"""

	private fun idOf(json: String) = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(json)!!.groupValues[1]

	private fun count() = jdbc.sql("SELECT count(*) FROM t_operations WHERE workspace_id = :id")
		.param("id", workspaceId)
		.query(Int::class.java).single()

	private companion object {
		val USER = TestWorkspaces.TEST_SUBJECT
		val OCCURRED_AT: LocalDateTime = LocalDateTime.parse("2026-03-15T14:30:00")
	}
}
