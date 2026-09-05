package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.api.v1.dto.CreateWorkspaceRequest
import com.github.melancholic.fintrace.core.api.v1.dto.EditWorkspaceRequest
import com.github.melancholic.fintrace.core.dao.UsersDAO
import com.github.melancholic.fintrace.core.dao.WorkspaceDAO
import com.github.melancholic.fintrace.core.domain.entity.WorkspaceStatus
import com.github.melancholic.fintrace.core.exception.ActionConflictException
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.context.support.WithMockUser
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The workspace lifecycle (§4.1.1) at the layer that owns it. The workspace is not event-sourced
 * (decision 1 of `docs/plans/M1.md`), so unlike the operation aggregate there is no event log to
 * cross-check against — the table is the record, and these tests are what pins its rules.
 *
 * Ownership runs through `IdentityProvider`, so every test here acts as the seeded stub user;
 * `otherUser` exists to prove a workspace belonging to someone else is invisible rather than
 * forbidden.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@WithMockUser(username = TestWorkspaces.TEST_SUBJECT)
class WorkspaceFacadeTest(
	@Autowired private val facade: WorkspaceFacade,
	@Autowired private val workspaceDAO: WorkspaceDAO,
	@Autowired private val usersDAO: UsersDAO,
	@Autowired private val jdbc: JdbcClient,
) {

	@BeforeEach
	fun clean() {
		TestWorkspaces.reset(jdbc)
	}

	@Test
	fun `creates a workspace owned by the caller, in NEW`() {
		val id = facade.createWorkspace(CreateWorkspaceRequest("budget", "EUR"))

		val created = facade.getWorkspace(id)
		assertEquals("budget", created.name)
		assertEquals("EUR", created.defaultCurrency)
		assertEquals(WorkspaceStatus.NEW, created.status, "import is permitted only from NEW (§4.2)")
		assertEquals(TestWorkspaces.ownerId(usersDAO), created.ownerId)
		assertEquals(0, created.version)
		assertNull(created.deletedAt)
	}

	@Test
	fun `never takes the owner from the caller's input`() {
		val id = facade.createWorkspace(CreateWorkspaceRequest("budget", "EUR"))

		// The owner is resolved server-side from the security context; there is no request field
		// that could carry a different one.
		assertEquals(TestWorkspaces.ownerId(usersDAO), facade.getWorkspace(id).ownerId)
	}

	@Test
	fun `hides a workspace owned by someone else`() {
		val theirs = workspaceDAO.create(otherUser(), CreateWorkspaceRequest("theirs", "USD"))

		// 404, not 403: an id you cannot reach and one that does not exist answer alike (§7.4).
		assertFailsWith<NotFoundEntityException> { facade.getWorkspace(theirs) }
		assertTrue(facade.getWorkspaces(page()).none { it.id == theirs })
	}

	@Test
	fun `fails to fetch a workspace that does not exist`() {
		assertFailsWith<NotFoundEntityException> { facade.getWorkspace(UUID.randomUUID()) }
	}

	@Test
	fun `lists only the caller's workspaces`() {
		val mine = facade.createWorkspace(CreateWorkspaceRequest("mine", "EUR"))
		workspaceDAO.create(otherUser(), CreateWorkspaceRequest("theirs", "USD"))

		assertEquals(listOf(mine), facade.getWorkspaces(page()).map { it.id })
	}

	@Test
	fun `sorts the listing by the requested column`() {
		val first = facade.createWorkspace(CreateWorkspaceRequest("alpha", "EUR"))
		val second = facade.createWorkspace(CreateWorkspaceRequest("beta", "EUR"))

		val ascending = facade.getWorkspaces(page(Sort.by("name"))).map { it.id }
		assertEquals(listOf(first, second), ascending)
		assertEquals(listOf(second, first), facade.getWorkspaces(page(Sort.by(Sort.Direction.DESC, "name"))).map { it.id })
	}

	@Test
	fun `pages the listing`() {
		repeat(3) { facade.createWorkspace(CreateWorkspaceRequest("ws-$it", "EUR")) }

		assertEquals(2, facade.getWorkspaces(PageRequest.of(0, 2, Sort.by("name"))).size)
		assertEquals(1, facade.getWorkspaces(PageRequest.of(1, 2, Sort.by("name"))).size)
	}

	@Test
	fun `renames a workspace and bumps its version`() {
		val id = facade.createWorkspace(CreateWorkspaceRequest("before", "EUR"))

		facade.editWorkspace(id, EditWorkspaceRequest(version = 0, workspaceName = "after", defaultCurrency = null))

		val edited = facade.getWorkspace(id)
		assertEquals("after", edited.name)
		assertEquals("EUR", edited.defaultCurrency, "an absent field leaves the stored value alone")
		assertEquals(1, edited.version)
	}

	@Test
	fun `changes the default currency alone`() {
		val id = facade.createWorkspace(CreateWorkspaceRequest("budget", "EUR"))

		facade.editWorkspace(id, EditWorkspaceRequest(version = 0, workspaceName = null, defaultCurrency = "USD"))

		assertEquals("budget", facade.getWorkspace(id).name)
		assertEquals("USD", facade.getWorkspace(id).defaultCurrency)
	}

	@Test
	fun `refuses an edit carrying a stale version`() {
		val id = facade.createWorkspace(CreateWorkspaceRequest("budget", "EUR"))
		facade.editWorkspace(id, EditWorkspaceRequest(version = 0, workspaceName = "first", defaultCurrency = null))

		// The client's view is one version behind: someone else has written since it read.
		assertFailsWith<ActionConflictException> {
			facade.editWorkspace(id, EditWorkspaceRequest(version = 0, workspaceName = "second", defaultCurrency = null))
		}

		assertEquals("first", facade.getWorkspace(id).name, "the losing write must not apply")
	}

	@Test
	fun `refuses to edit a workspace owned by someone else`() {
		val theirs = workspaceDAO.create(otherUser(), CreateWorkspaceRequest("theirs", "USD"))

		// Not a conflict: an id the caller cannot reach is reported as missing, so the status
		// discloses nothing about whose it is.
		assertFailsWith<NotFoundEntityException> {
			facade.editWorkspace(theirs, EditWorkspaceRequest(version = 0, workspaceName = "mine-now", defaultCurrency = null))
		}

		assertEquals("theirs", row(theirs).name)
	}

	@Test
	fun `archives an active workspace and unarchives it back`() {
		val id = activeWorkspace()

		facade.archiveWorkspace(id)
		assertEquals(WorkspaceStatus.ARCHIVED, facade.getWorkspace(id).status)

		facade.unarchiveWorkspace(id)
		assertEquals(WorkspaceStatus.ACTIVE, facade.getWorkspace(id).status, "archiving is reversible (§4.8)")
	}

	@Test
	fun `refuses to archive a workspace that is still NEW`() {
		val id = facade.createWorkspace(CreateWorkspaceRequest("budget", "EUR"))

		// A NEW workspace is empty by definition, so there is nothing to keep for reference —
		// and unarchiving would have to guess which status to return to.
		assertFailsWith<ActionConflictException> { facade.archiveWorkspace(id) }
		assertEquals(WorkspaceStatus.NEW, facade.getWorkspace(id).status)
	}

	@Test
	fun `unarchiving a workspace that is already active is a no-op`() {
		val id = activeWorkspace()

		// The service treats "already in the target state" as success, so only a transition that
		// cannot reach the target — archiving a NEW workspace — is a conflict.
		facade.unarchiveWorkspace(id)

		assertEquals(WorkspaceStatus.ACTIVE, facade.getWorkspace(id).status)
	}

	@Test
	fun `archiving twice is a no-op`() {
		val id = activeWorkspace()
		facade.archiveWorkspace(id)

		facade.archiveWorkspace(id)

		assertEquals(WorkspaceStatus.ARCHIVED, facade.getWorkspace(id).status)
	}

	@Test
	fun `deletes a NEW workspace`() {
		val id = facade.createWorkspace(CreateWorkspaceRequest("abandoned", "EUR"))

		facade.deleteWorkspace(id, versionOf(id))

		assertEquals(WorkspaceStatus.DELETED, row(id).status)
	}

	@Test
	fun `deletes an archived workspace`() {
		val id = activeWorkspace()
		facade.archiveWorkspace(id)

		facade.deleteWorkspace(id, versionOf(id))

		assertEquals(WorkspaceStatus.DELETED, row(id).status)
	}

	@Test
	fun `a deleted workspace is invisible to reads`() {
		val id = facade.createWorkspace(CreateWorkspaceRequest("gone", "EUR"))

		facade.deleteWorkspace(id, versionOf(id))

		// Soft delete, but terminal: the row survives for the retention job, not for the caller.
		assertFailsWith<NotFoundEntityException> { facade.getWorkspace(id) }
		assertTrue(facade.getWorkspaces(page()).none { it.id == id })
	}

	@Test
	fun `stamps deleted_at exactly when deleting`() {
		val id = facade.createWorkspace(CreateWorkspaceRequest("gone", "EUR"))
		assertNull(row(id).deletedAt)

		facade.deleteWorkspace(id, versionOf(id))

		// The retention job (1.5b) finds workspaces by this column, and the CHECK constraint ties
		// it to the status — a delete that left it null could not be purged.
		assertNotNull(row(id).deletedAt)
	}

	@Test
	fun `refuses to delete an already deleted workspace`() {
		val id = facade.createWorkspace(CreateWorkspaceRequest("gone", "EUR"))
		facade.deleteWorkspace(id, versionOf(id))

		// A deleted workspace is invisible, so the second attempt cannot even find it.
		assertFailsWith<NotFoundEntityException> { facade.deleteWorkspace(id, 1) }
	}

	@Test
	fun `refuses to delete against a stale version`() {
		val id = activeWorkspace()
		facade.archiveWorkspace(id)

		// The caller last saw version 0; archiving has moved it on. Deleting is terminal, so a
		// stale view is exactly what must not be allowed to act.
		assertFailsWith<ActionConflictException> { facade.deleteWorkspace(id, 0) }

		assertEquals(WorkspaceStatus.ARCHIVED, row(id).status, "the workspace must survive")
	}

	@Test
	fun `deletes against the current version`() {
		val id = activeWorkspace()
		facade.archiveWorkspace(id)

		facade.deleteWorkspace(id, versionOf(id))

		assertEquals(WorkspaceStatus.DELETED, row(id).status)
	}

	@Test
	fun `refuses to edit a deleted workspace`() {
		val id = facade.createWorkspace(CreateWorkspaceRequest("gone", "EUR"))
		facade.deleteWorkspace(id, versionOf(id))

		assertFailsWith<NotFoundEntityException> {
			facade.editWorkspace(id, EditWorkspaceRequest(version = 0, workspaceName = "back", defaultCurrency = null))
		}
	}

	@Test
	fun `a status change bumps the version too`() {
		val id = activeWorkspace()
		val before = facade.getWorkspace(id).version

		facade.archiveWorkspace(id)

		// Any write invalidates a client's held version, or "the state I last saw" stops meaning
		// anything as soon as a transition happens between a read and an edit.
		assertTrue(facade.getWorkspace(id).version > before)
	}

	private fun versionOf(id: UUID): Long = row(id).version

	private fun page(sort: Sort = Sort.by(Sort.Direction.DESC, "createdAt")) = PageRequest.of(0, 20, sort)

	/**
	 * Until task 1.4 adds the `NEW → ACTIVE` transition ("start empty", and the hook import will
	 * use), nothing in the API can move a workspace out of `NEW` — so the archive rules are only
	 * reachable by setting the status directly.
	 */
	private fun activeWorkspace(): UUID {
		val id = facade.createWorkspace(CreateWorkspaceRequest("active-ws", "EUR"))
		jdbc.sql("UPDATE t_workspaces SET status = 'ACTIVE' WHERE id = :id").param("id", id).update()
		return id
	}

	private fun otherUser(): UUID {
		val id = UUID.randomUUID()
		jdbc.sql(
			"""
			INSERT INTO t_users (id, external_id, username, created_at)
			VALUES (:id, :externalId, :username, :createdAt)
			ON CONFLICT (external_id) DO NOTHING
			"""
		)
			.param("id", id)
			.param("externalId", "stub:someone-else")
			.param("username", "someone-else")
			.param("createdAt", LocalDateTime.now())
			.update()

		// Raw SQL because users have no create path yet: the only row normally comes from the
		// migration's seed, and M5 replaces both (§7.4).
		return jdbc.sql("SELECT id FROM t_users WHERE external_id = 'stub:someone-else'")
			.query(UUID::class.java).single()
	}

	private fun row(id: UUID): WorkspaceRow = jdbc
		.sql("SELECT name, status, version, deleted_at FROM t_workspaces WHERE id = :id")
		.param("id", id)
		.query { rs, _ ->
			WorkspaceRow(
				name = rs.getString("name"),
				status = WorkspaceStatus.valueOf(rs.getString("status")),
				version = rs.getLong("version"),
				deletedAt = rs.getObject("deleted_at", LocalDateTime::class.java),
			)
		}
		.single()

	private data class WorkspaceRow(
		val name: String,
		val status: WorkspaceStatus,
		val version: Long,
		val deletedAt: LocalDateTime?,
	)
}
