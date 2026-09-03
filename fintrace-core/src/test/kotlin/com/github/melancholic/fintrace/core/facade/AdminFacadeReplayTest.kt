package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.domain.command.CancelOperationCommand
import com.github.melancholic.fintrace.core.domain.command.CreateOperationCommand
import com.github.melancholic.fintrace.core.domain.command.ReviseOperationCommand
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The full-rebuild procedure (task 0.11) and its equality guarantee (task 0.12).
 *
 * This is the one real payoff for the complexity event sourcing costs (§4.10): if the projection
 * cannot be reconstructed from the log, ES has degenerated into an audit log with extra steps.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class AdminFacadeReplayTest(
	@Autowired private val commandFacade: CommandFacade,
	@Autowired private val adminFacade: AdminFacade,
	@Autowired private val jdbc: JdbcClient,
) {

	@BeforeEach
	fun clean() {
		jdbc.sql("DELETE FROM t_operations").update()
		jdbc.sql("DELETE FROM t_events").update()
	}

	@Test
	fun `rebuilds a projection identical to the original`() {
		repeat(5) { create(amount = "10.${it}000") }
		val before = operations(WORKSPACE)

		adminFacade.replayWorkspace(WORKSPACE)

		assertEquals(before, operations(WORKSPACE))
		assertEquals(5, before.size, "sanity: the snapshot must not be empty")
	}

	@Test
	fun `reconstructs a projection that was wiped entirely`() {
		repeat(3) { create() }
		val before = operations(WORKSPACE)

		// Not just "replay agrees with itself" — the rows are gone, and only the event log
		// remains to rebuild them from.
		jdbc.sql("DELETE FROM t_operations").update()
		assertTrue(operations(WORKSPACE).isEmpty(), "sanity: the projection is empty")

		adminFacade.replayWorkspace(WORKSPACE)

		assertEquals(before, operations(WORKSPACE))
	}

	@Test
	fun `appends no events`() {
		repeat(3) { create() }
		val eventsBefore = eventIds()

		adminFacade.replayWorkspace(WORKSPACE)

		// Replay applies stored events; it must never travel the command path, which would
		// write new ones and corrupt the log it is rebuilding from.
		assertEquals(eventsBefore, eventIds())
	}

	@Test
	fun `is idempotent`() {
		repeat(3) { create() }

		adminFacade.replayWorkspace(WORKSPACE)
		val once = operations(WORKSPACE)
		adminFacade.replayWorkspace(WORKSPACE)

		assertEquals(once, operations(WORKSPACE))
	}

	@Test
	fun `leaves other workspaces untouched`() {
		val other = UUID.fromString("0199a1c2-3d4e-7f80-8123-000000000002")
		repeat(2) { create() }
		repeat(3) { create(workspaceId = other) }
		val otherBefore = operations(other)

		adminFacade.replayWorkspace(WORKSPACE)

		assertEquals(2, operations(WORKSPACE).size)
		assertEquals(otherBefore, operations(other), "replay is scoped to one workspace")
	}

	@Test
	fun `replaying a workspace with no events is a no-op`() {
		adminFacade.replayWorkspace(UUID.randomUUID())

		assertEquals(0, operations(WORKSPACE).size)
	}

	@Test
	fun `replays a revision onto the same row`() {
		val id = create()
		commandFacade.processCommand(
			ReviseOperationCommand(
				workspaceId = WORKSPACE, operationId = id,
				occurredAt = OCCURRED_AT, amount = BigDecimal("777.0000"),
			)
		)
		val before = operations(WORKSPACE)

		jdbc.sql("DELETE FROM t_operations").update()
		adminFacade.replayWorkspace(WORKSPACE)

		// Two events, one row: the create inserts and the revision overwrites, which only holds
		// if applying an event is an upsert rather than an insert.
		assertEquals(before, operations(WORKSPACE))
		assertEquals(1, operations(WORKSPACE).size)
		assertEquals(BigDecimal("777.0000"), operations(WORKSPACE).single().amount)
	}

	@Test
	fun `replays a cancellation as an absent row`() {
		val id = create()
		commandFacade.processCommand(
			CancelOperationCommand(workspaceId = WORKSPACE, operationId = id, occurredAt = OCCURRED_AT)
		)

		jdbc.sql("DELETE FROM t_operations").update()
		adminFacade.replayWorkspace(WORKSPACE)

		// The log still holds both events; the rebuilt projection must not hold the row.
		assertEquals(2, eventIds().size)
		assertTrue(operations(WORKSPACE).isEmpty(), "a cancelled operation must not come back")
	}

	@Test
	fun `rebuilds a mixed history identically`() {
		val revised = create(amount = "10.0000")
		val cancelled = create(amount = "20.0000")
		create(amount = "30.0000")
		commandFacade.processCommand(
			ReviseOperationCommand(
				workspaceId = WORKSPACE, operationId = revised,
				occurredAt = BACK_DATED, amount = BigDecimal("11.0000"),
			)
		)
		commandFacade.processCommand(
			CancelOperationCommand(
				workspaceId = WORKSPACE, operationId = cancelled, occurredAt = OCCURRED_AT,
			)
		)
		val before = operations(WORKSPACE)

		adminFacade.replayWorkspace(WORKSPACE)

		assertEquals(before, operations(WORKSPACE))
		assertEquals(2, before.size, "sanity: one of the three was cancelled")
	}

	@Test
	fun `preserves amount and timestamps exactly`() {
		create(amount = "-1234.5600", occurredAt = BACK_DATED)

		adminFacade.replayWorkspace(WORKSPACE)

		val row = operations(WORKSPACE).single()
		assertEquals(BigDecimal("-1234.5600"), row.amount)
		assertEquals(4, row.amount.scale())
		assertEquals(BACK_DATED, row.occurredAt)
	}

	private fun create(
		workspaceId: UUID = WORKSPACE,
		amount: String = "100.0000",
		occurredAt: LocalDateTime = OCCURRED_AT,
	) = commandFacade.processCommand(
		CreateOperationCommand(workspaceId, occurredAt, BigDecimal(amount))
	)

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

	private data class Row(
		val id: UUID,
		val workspaceId: UUID,
		val amount: BigDecimal,
		val occurredAt: LocalDateTime,
		val recordedAt: LocalDateTime,
	)

	private companion object {
		val WORKSPACE: UUID = UUID.fromString("0199a1c2-3d4e-7f80-8123-000000000001")
		val OCCURRED_AT: LocalDateTime = LocalDateTime.parse("2026-03-15T14:30:00")
		val BACK_DATED: LocalDateTime = LocalDateTime.parse("2020-01-01T08:00:00")
	}
}
