package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.api.v1.dto.CreateWorkspaceRequest
import com.github.melancholic.fintrace.core.dao.WorkspaceDAO
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import jakarta.servlet.ServletException
import java.net.URI
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The HTTP contract for `/api/v1/workspaces`.
 *
 * Runs the real filter chain against a real Postgres, so routing, serialisation, bean validation
 * and security are all exercised — none of which a standalone controller test would reach.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class WorkspacesRestControllerTest(
	@Autowired private val mvc: MockMvc,
	@Autowired private val jdbc: JdbcClient,
	@Autowired private val workspaceDAO: WorkspaceDAO,
) {

	@BeforeEach
	fun clean() {
		TestWorkspaces.reset(jdbc)
	}

	@Test
	fun `creates a workspace and returns its id`() {
		mvc.perform(createRequest())
			.andExpect(status().isCreated)
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.id").exists())
	}

	@Test
	fun `points the Location header at the created workspace`() {
		val response = mvc.perform(createRequest()).andExpect(status().isCreated).andReturn().response
		val location = URI.create(response.getHeader("Location")!!).path

		assertEquals("$WORKSPACES_PATH/${idOf(response.contentAsString)}", location)

		// A Location header is only useful if it resolves — follow it.
		mvc.perform(get(location).with(user(USER))).andExpect(status().isOk)
	}

	@Test
	fun `returns a new workspace in NEW, owned by the caller`() {
		val id = createdId()

		mvc.perform(get("$WORKSPACES_PATH/$id").with(user(USER)))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.id").value(id.toString()))
			.andExpect(jsonPath("$.name").value("budget"))
			.andExpect(jsonPath("$.defaultCurrency").value("EUR"))
			.andExpect(jsonPath("$.status").value("NEW"))
			.andExpect(jsonPath("$.version").value(0))
			.andExpect(jsonPath("$.deletedAt").doesNotExist())
	}

	@Test
	fun `lists the caller's workspaces`() {
		val mine = createdId()
		workspaceDAO.create(otherUser(), CreateWorkspaceRequest("theirs", "USD"))

		mvc.perform(get(WORKSPACES_PATH).with(user(USER)))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].id").value(mine.toString()))
	}

	@Test
	fun `sorts the listing by a requested column`() {
		createdId(name = "beta")
		createdId(name = "alpha")

		mvc.perform(get("$WORKSPACES_PATH?sort=name,asc").with(user(USER)))
			.andExpect(jsonPath("$[0].name").value("alpha"))
			.andExpect(jsonPath("$[1].name").value("beta"))
	}

	@Test
	fun `rejects sorting by a column that is not sortable`() {
		createdId()

		// The sort property is concatenated into SQL, so anything outside the whitelist has to be
		// refused rather than passed through.
		mvc.perform(get("$WORKSPACES_PATH?sort=ownerId,asc").with(user(USER)))
			.andExpect(status().isBadRequest)
	}

	@Test
	fun `hides a workspace owned by someone else`() {
		val theirs = workspaceDAO.create(otherUser(), CreateWorkspaceRequest("theirs", "USD"))

		// 404 rather than 403: an id the caller cannot reach is indistinguishable from one that
		// does not exist, so nothing is disclosed by the status.
		mvc.perform(get("$WORKSPACES_PATH/$theirs").with(user(USER)))
			.andExpect(status().isNotFound)
	}

	@Test
	fun `returns 404 for a workspace that does not exist`() {
		mvc.perform(get("$WORKSPACES_PATH/${UUID.randomUUID()}").with(user(USER)))
			.andExpect(status().isNotFound)
	}

	@Test
	fun `edits a workspace and returns the new state`() {
		val id = createdId()

		// The response carries the bumped version, so a client can chain a second conditional
		// write without a fetch in between.
		mvc.perform(editRequest(id, body = """{"version":0,"workspaceName":"renamed"}"""))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.name").value("renamed"))
			.andExpect(jsonPath("$.defaultCurrency").value("EUR"))
			.andExpect(jsonPath("$.version").value(1))

		mvc.perform(get("$WORKSPACES_PATH/$id").with(user(USER)))
			.andExpect(jsonPath("$.name").value("renamed"))
			.andExpect(jsonPath("$.version").value(1))
	}

	@Test
	fun `an edit with no fields still counts as a write`() {
		val id = createdId()

		// The client asserted a state, so the version moves and the stored values do not — the
		// same call on operations appends an event rather than being swallowed (§4.4).
		mvc.perform(editRequest(id, body = """{"version":0}"""))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.version").value(1))
			.andExpect(jsonPath("$.name").value("budget"))
			.andExpect(jsonPath("$.defaultCurrency").value("EUR"))
	}

	@Test
	fun `an edit with no fields still checks the version`() {
		val id = createdId()
		mvc.perform(editRequest(id, body = """{"version":0,"workspaceName":"renamed"}"""))
			.andExpect(status().isOk)

		mvc.perform(editRequest(id, body = """{"version":0}"""))
			.andExpect(status().isConflict)
	}

	@Test
	fun `rejects an edit with a stale version`() {
		val id = createdId()
		mvc.perform(editRequest(id, body = """{"version":0,"workspaceName":"first"}"""))
			.andExpect(status().isOk)

		// The losing write is refused as a conflict, and the winning value stays.
		mvc.perform(editRequest(id, body = """{"version":0,"workspaceName":"second"}"""))
			.andExpect(status().isConflict)

		mvc.perform(get("$WORKSPACES_PATH/$id").with(user(USER)))
			.andExpect(jsonPath("$.name").value("first"), )
	}

	@Test
	fun `rejects an edit with a negative version`() {
		val id = createdId()

		mvc.perform(editRequest(id, body = """{"version":-1,"workspaceName":"renamed"}"""))
			.andExpect(status().isBadRequest)
	}

	@Test
	fun `rejects an edit with no version at all`() {
		val id = createdId()

		// The version is what makes a concurrent edit detectable; a client must not be able to
		// opt out of it by omitting the field.
		mvc.perform(editRequest(id, body = """{"workspaceName":"renamed"}"""))
			.andExpect(status().isBadRequest)
	}

	@Test
	fun `rejects a name outside the permitted pattern`() {
		mvc.perform(createRequest(body = """{"workspaceName":"bad name!","defaultCurrency":"EUR"}"""))
			.andExpect(status().isBadRequest)
	}

	@Test
	fun `rejects a blank name`() {
		mvc.perform(createRequest(body = """{"workspaceName":"","defaultCurrency":"EUR"}"""))
			.andExpect(status().isBadRequest)
	}

	@Test
	fun `rejects a currency that is not an ISO code`() {
		mvc.perform(createRequest(body = """{"workspaceName":"budget","defaultCurrency":"eur"}"""))
			.andExpect(status().isBadRequest)
		mvc.perform(createRequest(body = """{"workspaceName":"budget","defaultCurrency":"EURO"}"""))
			.andExpect(status().isBadRequest)
	}

	@Test
	fun `archives and unarchives a workspace`() {
		val id = activeWorkspace()

		// Both transitions answer with the workspace, so the caller sees the new status and the
		// version it must send next.
		mvc.perform(post("$WORKSPACES_PATH/$id/archive").with(user(USER)).with(csrf()))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.status").value("ARCHIVED"))
		mvc.perform(get("$WORKSPACES_PATH/$id").with(user(USER)))
			.andExpect(jsonPath("$.status").value("ARCHIVED"))

		mvc.perform(delete("$WORKSPACES_PATH/$id/archive").with(user(USER)).with(csrf()))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.status").value("ACTIVE"))
		mvc.perform(get("$WORKSPACES_PATH/$id").with(user(USER)))
			.andExpect(jsonPath("$.status").value("ACTIVE"))
	}

	@Test
	fun `deletes a workspace and hides it afterwards`() {
		val id = createdId()

		mvc.perform(delete("$WORKSPACES_PATH/$id?version=0").with(user(USER)).with(csrf()))
			.andExpect(status().isNoContent)

		mvc.perform(get("$WORKSPACES_PATH/$id").with(user(USER)))
			.andExpect(status().isNotFound)
		mvc.perform(get(WORKSPACES_PATH).with(user(USER)))
			.andExpect(jsonPath("$.length()").value(0))
	}

	@Test
	fun `rejects a delete carrying a stale version`() {
		val id = createdId()
		mvc.perform(editRequest(id, body = """{"version":0,"workspaceName":"renamed"}"""))
			.andExpect(status().isOk)

		mvc.perform(delete("$WORKSPACES_PATH/$id?version=0").with(user(USER)).with(csrf()))
			.andExpect(status().isConflict)

		mvc.perform(get("$WORKSPACES_PATH/$id").with(user(USER))).andExpect(status().isOk)
	}

	@Test
	fun `rejects a delete with no version at all`() {
		val id = createdId()

		// Deletion is terminal, so it must not be reachable by omitting the guard.
		mvc.perform(delete("$WORKSPACES_PATH/$id").with(user(USER)).with(csrf()))
			.andExpect(status().isBadRequest)

		mvc.perform(get("$WORKSPACES_PATH/$id").with(user(USER))).andExpect(status().isOk)
	}

	@Test
	fun `rejects unauthenticated access to every endpoint`() {
		val id = createdId()

		mvc.perform(post(WORKSPACES_PATH).contentType(MediaType.APPLICATION_JSON).content(createBody()))
			.andExpect(status().isForbidden)
		mvc.perform(get(WORKSPACES_PATH)).andExpect(status().isForbidden)
		mvc.perform(get("$WORKSPACES_PATH/$id")).andExpect(status().isForbidden)
		mvc.perform(
			put("$WORKSPACES_PATH/$id").contentType(MediaType.APPLICATION_JSON)
				.content("""{"version":0,"workspaceName":"renamed"}""")
		).andExpect(status().isForbidden)
		mvc.perform(delete("$WORKSPACES_PATH/$id?version=0")).andExpect(status().isForbidden)

		assertEquals(1, count(), "nothing may be created or removed for an unauthenticated caller")
	}

	private fun createRequest(body: String = createBody()) = post(WORKSPACES_PATH)
		.with(user(USER))
		.with(csrf())
		.contentType(MediaType.APPLICATION_JSON)
		.content(body)

	private fun editRequest(id: UUID, body: String) = put("$WORKSPACES_PATH/$id")
		.with(user(USER))
		.with(csrf())
		.contentType(MediaType.APPLICATION_JSON)
		.content(body)

	private fun createBody(name: String = "budget", currency: String = "EUR") =
		"""{"workspaceName":"$name","defaultCurrency":"$currency"}"""

	private fun createdId(name: String = "budget"): UUID =
		idOf(mvc.perform(createRequest(createBody(name))).andExpect(status().isCreated).andReturn().response.contentAsString)

	/** Until task 1.4 adds `NEW → ACTIVE`, the status can only be moved directly. */
	private fun activeWorkspace(): UUID = createdId().also {
		jdbc.sql("UPDATE t_workspaces SET status = 'ACTIVE' WHERE id = :id").param("id", it).update()
	}

	private fun otherUser(): UUID {
		jdbc.sql(
			"""
			INSERT INTO t_users (id, external_id, username, created_at)
			VALUES (:id, 'stub:someone-else', 'someone-else', :createdAt)
			ON CONFLICT (external_id) DO NOTHING
			"""
		)
			.param("id", UUID.randomUUID())
			.param("createdAt", LocalDateTime.now())
			.update()

		return jdbc.sql("SELECT id FROM t_users WHERE external_id = 'stub:someone-else'")
			.query(UUID::class.java).single()
	}

	private fun count() = jdbc.sql("SELECT count(*) FROM t_workspaces WHERE status <> 'DELETED'")
		.query(Int::class.java).single()

	private fun idOf(json: String): UUID =
		UUID.fromString(Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(json)!!.groupValues[1])

	private companion object {
		val USER = TestWorkspaces.TEST_SUBJECT
		const val WORKSPACES_PATH = "/api/v1/workspaces"
	}
}
