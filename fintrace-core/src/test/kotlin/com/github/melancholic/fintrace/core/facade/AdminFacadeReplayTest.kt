package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.dao.UsersDAO
import com.github.melancholic.fintrace.core.dao.WorkspaceDAO
import com.github.melancholic.fintrace.core.domain.command.*
import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import com.github.melancholic.fintrace.core.service.WorkspaceService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The full-rebuild procedure (task 0.11) and its equality guarantee (task 0.12).
 *
 * This is the one real payoff for the complexity event sourcing costs (§4.10): if the projection
 * cannot be reconstructed from the log, ES has degenerated into an audit log with extra steps.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@WithMockUser(username = TestWorkspaces.TEST_SUBJECT)
class AdminFacadeReplayTest(
	@Autowired private val commandFacade: CommandFacade,
	@Autowired private val adminFacade: AdminFacade,
	@Autowired private val jdbc: JdbcClient,
	@Autowired private val workspaceDAO: WorkspaceDAO,
	@Autowired private val usersDAO: UsersDAO,
    @Autowired private val workspaceService: WorkspaceService,
    @Autowired private val transactions: TransactionTemplate,
) {

	private lateinit var workspace: UUID

	@BeforeEach
	fun clean() {
		TestWorkspaces.reset(jdbc)
		workspace = TestWorkspaces.create(workspaceDAO, usersDAO)
	}

	@Test
	fun `rebuilds a projection identical to the original`() {
		repeat(5) { create(amount = "10.${it}000") }
		val before = operations(workspace)

		adminFacade.replayWorkspace(workspace)

		assertEquals(before, operations(workspace))
		assertEquals(5, before.size, "sanity: the snapshot must not be empty")
	}

	@Test
	fun `reconstructs a projection that was wiped entirely`() {
		repeat(3) { create() }
		val before = operations(workspace)

		// Not just "replay agrees with itself" — the rows are gone, and only the event log
		// remains to rebuild them from.
		jdbc.sql("DELETE FROM t_operations").update()
		assertTrue(operations(workspace).isEmpty(), "sanity: the projection is empty")

		adminFacade.replayWorkspace(workspace)

		assertEquals(before, operations(workspace))
	}

	@Test
	fun `appends no events`() {
		repeat(3) { create() }
		val eventsBefore = eventIds()

		adminFacade.replayWorkspace(workspace)

		// Replay applies stored events; it must never travel the command path, which would
		// write new ones and corrupt the log it is rebuilding from.
		assertEquals(eventsBefore, eventIds())
	}

	@Test
	fun `is idempotent`() {
		repeat(3) { create() }

		adminFacade.replayWorkspace(workspace)
		val once = operations(workspace)
		adminFacade.replayWorkspace(workspace)

		assertEquals(once, operations(workspace))
	}

	@Test
	fun `leaves other workspaces untouched`() {
		val other = TestWorkspaces.create(workspaceDAO, usersDAO, name = "other-workspace")
		repeat(2) { create() }
		repeat(3) { create(workspaceId = other) }
		val otherBefore = operations(other)

		adminFacade.replayWorkspace(workspace)

		assertEquals(2, operations(workspace).size)
		assertEquals(otherBefore, operations(other), "replay is scoped to one workspace")
	}

	@Test
	fun `replaying a workspace with no events is a no-op`() {
		adminFacade.replayWorkspace(UUID.randomUUID())

		assertEquals(0, operations(workspace).size)
	}

	@Test
	fun `replays a revision onto the same row`() {
		val id = create()
		commandFacade.processCommand(
			ReviseOperationCommand(
				workspaceId = workspace, operationId = id,
				occurredAt = OCCURRED_AT, amount = BigDecimal("777.0000"),
			)
		)
		val before = operations(workspace)

		jdbc.sql("DELETE FROM t_operations").update()
		adminFacade.replayWorkspace(workspace)

		// Two events, one row: the create inserts and the revision overwrites, which only holds
		// if applying an event is an upsert rather than an insert.
		assertEquals(before, operations(workspace))
		assertEquals(1, operations(workspace).size)
		assertEquals(BigDecimal("777.0000"), operations(workspace).single().amount)
	}

	@Test
	fun `replays a cancellation as an absent row`() {
		val id = create()
		commandFacade.processCommand(
			CancelOperationCommand(workspaceId = workspace, operationId = id)
		)

		jdbc.sql("DELETE FROM t_operations").update()
		adminFacade.replayWorkspace(workspace)

		// The log still holds both events; the rebuilt projection must not hold the row.
		assertEquals(2, eventIds().size)
		assertTrue(operations(workspace).isEmpty(), "a cancelled operation must not come back")
	}

	@Test
	fun `rebuilds a mixed history identically`() {
		val revised = create(amount = "10.0000")
		val cancelled = create(amount = "20.0000")
		create(amount = "30.0000")
		commandFacade.processCommand(
			ReviseOperationCommand(
				workspaceId = workspace, operationId = revised,
				occurredAt = BACK_DATED, amount = BigDecimal("11.0000"),
			)
		)
		commandFacade.processCommand(
			CancelOperationCommand(workspaceId = workspace, operationId = cancelled)
		)
		val before = operations(workspace)

		adminFacade.replayWorkspace(workspace)

		assertEquals(before, operations(workspace))
		assertEquals(2, before.size, "sanity: one of the three was cancelled")
	}

	@Test
	fun `rebuilds two aggregates at once`() {
		repeat(2) { create() }
		createAccount(name = "cash")
		createAccount(name = "savings")
		val operationsBefore = operations(workspace)
		val accountsBefore = accounts(workspace)

		// Until now nothing proved the replay path generalises: one table can be rebuilt by a
		// handler that happens to be right, two cannot.
		jdbc.sql("DELETE FROM t_operations").update()
		jdbc.sql("DELETE FROM t_accounts").update()
		adminFacade.replayWorkspace(workspace)

		assertEquals(operationsBefore, operations(workspace))
		assertEquals(accountsBefore, accounts(workspace))
		assertEquals(2, accountsBefore.size, "sanity: the snapshot must not be empty")
	}

	@Test
	fun `replays each aggregate through its own projection`() {
		val account = createAccount(name = "cash")
		create()

		adminFacade.replayWorkspace(workspace)

		// A payload registered under the wrong discriminator would rebuild as the other
		// aggregate, leaving one table short and the other with a row it cannot map.
		assertEquals(1, accounts(workspace).size)
		assertEquals(1, operations(workspace).size)
		assertEquals(account, accounts(workspace).single().id)
	}

	@Test
	fun `replays an account's revisions onto one row`() {
		val account = createAccount(name = "before", currency = "CZK")
		commandFacade.processCommand(ReviseAccountCommand(workspace, account, "after", icon = "new"))
		commandFacade.processCommand(SetAccountArchivedCommand(workspace, account, archived = true))
		val before = accounts(workspace)

		jdbc.sql("DELETE FROM t_accounts").update()
		adminFacade.replayWorkspace(workspace)

		assertEquals(before, accounts(workspace))
		val row = accounts(workspace).single()
		assertEquals("after", row.name)
		assertEquals("CZK", row.currency, "the immutable currency survives three events")
		assertTrue(row.archived)
	}

    @Test
    fun `rebuilds all three aggregates at once`() {
        // A seeded workspace, so the four system categories are part of what has to come back.
        val seeded = TestWorkspaces.createWithCategories(transactions, workspaceService, usersDAO, name = "seeded")
        val food = createCategory(seeded, parent = expenseRoot(seeded), name = "Food")
        createCategory(seeded, parent = food, name = "Groceries")
        commandFacade.processCommand(CreateAccountCommand(seeded, "cash", "EUR", icon = null))
        create(workspaceId = seeded)
        val categoriesBefore = categories(seeded)
        val accountsBefore = accounts(seeded)
        val operationsBefore = operations(seeded)

        jdbc.sql("DELETE FROM t_categories").update()
        jdbc.sql("DELETE FROM t_accounts").update()
        jdbc.sql("DELETE FROM t_operations").update()
        adminFacade.replayWorkspace(seeded)

        assertEquals(categoriesBefore, categories(seeded))
        assertEquals(accountsBefore, accounts(seeded))
        assertEquals(operationsBefore, operations(seeded))
        assertEquals(6, categoriesBefore.size, "sanity: four seeded plus two of our own")
    }

    @Test
	fun `rebuilds the tree structure and the system codes`() {
        val seeded = TestWorkspaces.createWithCategories(transactions, workspaceService, usersDAO, name = "seeded")
        val root = expenseRoot(seeded)
        val food = createCategory(seeded, parent = root, name = "Food")
        createCategory(seeded, parent = food, name = "Groceries")

        jdbc.sql("DELETE FROM t_categories").update()
        adminFacade.replayWorkspace(seeded)

        // parent_id is the tree: if a rebuild loses it, the projection is a flat list that still
        // passes a row-count assertion.
        val rebuilt = categories(seeded).associateBy { it.id }
        assertEquals(root, rebuilt.getValue(food).parentId)
        assertEquals(food, rebuilt.values.single { it.name == "Groceries" }.parentId)
        assertEquals(null, rebuilt.getValue(root).parentId, "a root keeps its null parent")
		assertEquals(
			"EXPENSE_ROOT",
			rebuilt.getValue(root).systemCode,
			"a seeded root rebuilds with the code it was seeded with, not merely as 'a system category'",
		)
		assertNull(rebuilt.getValue(food).systemCode)
    }

    @Test
    fun `rebuilds an archived subtree`() {
        val seeded = TestWorkspaces.createWithCategories(transactions, workspaceService, usersDAO, name = "seeded")
        val food = createCategory(seeded, parent = expenseRoot(seeded), name = "Food")
        val groceries = createCategory(seeded, parent = food, name = "Groceries")
        commandFacade.processCommand(SetCategoryArchivedCommand(seeded, food, archived = true))
        val before = categories(seeded)

        jdbc.sql("DELETE FROM t_categories").update()
        adminFacade.replayWorkspace(seeded)

        // The cascade wrote one event per node (§4.7). Replaying only the event addressed to the
        // parent would bring Groceries back unarchived.
        assertEquals(before, categories(seeded))
        val rebuilt = categories(seeded).associateBy { it.id }
        assertTrue(rebuilt.getValue(food).archived)
        assertTrue(rebuilt.getValue(groceries).archived, "the child's own event carries its archived state")
    }

	@Test
	fun `preserves amount and timestamps exactly`() {
		create(amount = "-1234.5600", occurredAt = BACK_DATED)

		adminFacade.replayWorkspace(workspace)

		val row = operations(workspace).single()
		assertEquals(BigDecimal("-1234.5600"), row.amount)
		assertEquals(4, row.amount.scale())
		assertEquals(BACK_DATED, row.occurredAt)
	}

	private fun create(
		workspaceId: UUID = workspace,
		amount: String = "100.0000",
		occurredAt: LocalDateTime = OCCURRED_AT,
    ): UUID = commandFacade.processCommand(
		CreateOperationCommand(workspaceId, occurredAt, BigDecimal(amount))
    ).id

	private fun createAccount(
		name: String = "account",
		currency: String = "EUR",
	): UUID = commandFacade.processCommand(CreateAccountCommand(workspace, name, currency, icon = null)).id

    private fun createCategory(workspaceId: UUID, parent: UUID, name: String): UUID = commandFacade
        .processCommand(
            CreateCategoryCommand.custom(workspaceId, CategoryKind.EXPENSE, name, parent, icon = null)
        ).id

    private fun expenseRoot(workspaceId: UUID): UUID = jdbc
        .sql("SELECT id FROM t_categories WHERE workspace_id = :ws AND parent_id IS NULL AND kind = 'EXPENSE'")
        .param("ws", workspaceId)
        .query(UUID::class.java)
        .single()

    private fun categories(workspaceId: UUID): List<CategoryRow> = jdbc
        .sql(
            """
			SELECT id, workspace_id, parent_id, name, kind, icon, archived, system_code, recorded_at
			FROM t_categories WHERE workspace_id = :ws ORDER BY id
			"""
        )
        .param("ws", workspaceId)
        .query { rs, _ ->
            CategoryRow(
                id = rs.getObject("id", UUID::class.java),
                workspaceId = rs.getObject("workspace_id", UUID::class.java),
                parentId = rs.getObject("parent_id", UUID::class.java),
                name = rs.getString("name"),
                kind = rs.getString("kind"),
                icon = rs.getString("icon"),
                archived = rs.getBoolean("archived"),
				systemCode = rs.getString("system_code"),
                recordedAt = rs.getObject("recorded_at", LocalDateTime::class.java),
            )
        }
        .list()

	private fun accounts(workspaceId: UUID): List<AccountRow> = jdbc
		.sql(
			"""
			SELECT id, workspace_id, name, currency, icon, archived, recorded_at
			FROM t_accounts WHERE workspace_id = :ws ORDER BY id
			"""
		)
		.param("ws", workspaceId)
		.query { rs, _ ->
			AccountRow(
				id = rs.getObject("id", UUID::class.java),
				workspaceId = rs.getObject("workspace_id", UUID::class.java),
				name = rs.getString("name"),
				currency = rs.getString("currency").trim(),
				icon = rs.getString("icon"),
				archived = rs.getBoolean("archived"),
				recordedAt = rs.getObject("recorded_at", LocalDateTime::class.java),
			)
		}
		.list()

	private fun operations(workspaceId: UUID): List<Row> = jdbc
		.sql(
			"""
			SELECT id, workspace_id, amount, occurred_at, recorded_at
			FROM t_operations WHERE workspace_id = :ws ORDER BY id
			"""
		)
		.param("ws", workspaceId)
		.query { rs, _ ->
			Row(
				id = rs.getObject("id", UUID::class.java),
				workspaceId = rs.getObject("workspace_id", UUID::class.java),
				amount = rs.getBigDecimal("amount"),
				occurredAt = rs.getObject("occurred_at", LocalDateTime::class.java),
				recordedAt = rs.getObject("recorded_at", LocalDateTime::class.java),
			)
		}
		.list()

	private fun eventIds(): List<Long> =
		jdbc.sql("SELECT id FROM t_events ORDER BY id").query(Long::class.java).list().filterNotNull()

	private data class AccountRow(
		val id: UUID,
		val workspaceId: UUID,
		val name: String,
		val currency: String,
		val icon: String?,
		val archived: Boolean,
		val recordedAt: LocalDateTime,
	)

    private data class CategoryRow(
        val id: UUID,
        val workspaceId: UUID,
        val parentId: UUID?,
        val name: String,
        val kind: String,
        val icon: String?,
        val archived: Boolean,
        val systemCode: String?,
        val recordedAt: LocalDateTime,
    )

	private data class Row(
		val id: UUID,
		val workspaceId: UUID,
		val amount: BigDecimal,
		val occurredAt: LocalDateTime,
		val recordedAt: LocalDateTime,
	)

	private companion object {
		val OCCURRED_AT: LocalDateTime = LocalDateTime.parse("2026-03-15T14:30:00")
		val BACK_DATED: LocalDateTime = LocalDateTime.parse("2020-01-01T08:00:00")
	}
}
