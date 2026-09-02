package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.domain.command.CreateOperationCommand
import com.github.melancholic.fintrace.core.domain.command.ReviseOperationCommand
import com.github.melancholic.fintrace.core.domain.event.payload.CreatedOperationEventPayloadV1
import com.github.melancholic.fintrace.core.domain.event.payload.EventPayload
import com.github.melancholic.fintrace.core.util.TimestampProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the whole write path through its real entry point against a real Postgres, because
 * most of what can break here is SQL, `jsonb` binding and the transaction boundary — none of
 * which a mocked test would reach.
 */
@Import(TestcontainersConfiguration::class, CommandFacadeIntegrationTest.FixedClock::class)
@SpringBootTest
class CommandFacadeIntegrationTest(
	@Autowired private val facade: CommandFacade,
	@Autowired private val jdbc: JdbcClient,
	@Autowired private val mapper: ObjectMapper,
) {

	@TestConfiguration
	class FixedClock {
		@Bean
		@Primary
		fun fixedTimestampProvider() = object : TimestampProvider {
			override fun now(): LocalDateTime = RECORDED_AT
		}
	}

	@BeforeEach
	fun clean() {
		jdbc.sql("DELETE FROM t_operations").update()
		jdbc.sql("DELETE FROM t_events").update()
	}

	@Test
	fun `writes exactly one event and one projection row`() {
		facade.processCommand(command())

		assertEquals(1, count("t_events"))
		assertEquals(1, count("t_operations"))
	}

	@Test
	fun `returns the entity id shared by the event and the projection`() {
		val returned = facade.processCommand(command())

		assertEquals(returned, event().entityId, "events.aggregate_id")
		assertEquals(returned, operation().id, "operations.id")
		assertEquals(returned, payload().id, "payload id")
	}

	@Test
	fun `classifies the event`() {
		facade.processCommand(command())

		val event = event()
		assertEquals("OPERATION", event.entityType)
		assertEquals("CREATED", event.eventType)
	}

	@Test
	fun `stores the payload as readable jsonb`() {
		facade.processCommand(command())

		val json = mapper.readTree(event().payload)
		assertEquals(CreatedOperationEventPayloadV1.TYPE, json.get("type").asString())
		assertEquals(CreatedOperationEventPayloadV1.VERSION, json.get("version").asInt())

		// Deserialised as EventPayload — the way a rebuild will read it back, knowing only
		// that the column holds some payload.
		assertTrue(payload() is CreatedOperationEventPayloadV1)
	}

	@Test
	fun `keeps occurred_at from the command and recorded_at from the clock`() {
		facade.processCommand(command())

		val event = event()
		assertEquals(OCCURRED_AT, event.occurredAt)
		assertEquals(RECORDED_AT, event.recordedAt)

		// §6.2: occurred_at is user-supplied and freely back-dated; recorded_at is when it
		// entered the system. They are independent, and here deliberately different.
		assertEquals(OCCURRED_AT, operation().occurredAt)
		assertEquals(RECORDED_AT, operation().recordedAt)
	}

	@Test
	fun `agrees on recorded_at across envelope, payload and projection`() {
		facade.processCommand(command())

		// One clock read per command: a second call would put microseconds between these.
		assertEquals(RECORDED_AT, event().recordedAt)
		assertEquals(RECORDED_AT, payload().recordedAt)
		assertEquals(RECORDED_AT, operation().recordedAt)
	}

	@Test
	fun `preserves the amount exactly, sign and scale included`() {
		facade.processCommand(command(amount = BigDecimal("-1234.5600")))

		val stored = operation().amount
		assertEquals(BigDecimal("-1234.5600"), stored)
		assertEquals(4, stored.scale())
		assertTrue(stored.signum() < 0, "expenses stay negative (§4.13)")
	}

	@Test
	fun `back-dating is accepted`() {
		val backDated = LocalDateTime.parse("2020-01-01T08:00:00")

		facade.processCommand(command(occurredAt = backDated))

		// §6.1: retrospective entry is the norm, not an error path.
		assertEquals(backDated, operation().occurredAt)
		assertTrue(operation().recordedAt > operation().occurredAt)
	}

	@Test
	fun `each command gets its own entity id and event sequence`() {
		val first = facade.processCommand(command())
		val second = facade.processCommand(command())

		assertTrue(first != second, "entity ids must be distinct")
		assertEquals(2, count("t_events"))
		assertEquals(2, count("t_operations"))

		val ids = jdbc.sql("SELECT id FROM t_events ORDER BY id")
			.query(Long::class.java).list().filterNotNull()
		assertEquals(ids.sorted(), ids, "events.id is the global order a rebuild replays by")
	}

	@Test
	fun `isolates workspaces`() {
		val other = UUID.randomUUID()

		facade.processCommand(command())
		facade.processCommand(command(workspaceId = other))

		assertEquals(1, countIn(WORKSPACE_ID))
		assertEquals(1, countIn(other))
	}

	@Test
	fun `rejects a command with no registered handler, writing nothing`() {
		// ReviseOperationCommand exists but has no handler yet (task 1.17).
		assertFailsWith<IllegalArgumentException> {
			facade.processCommand(ReviseOperationCommand(WORKSPACE_ID, OCCURRED_AT))
		}

		assertEquals(0, count("t_events"))
		assertEquals(0, count("t_operations"))
	}

	private fun command(
		workspaceId: UUID = WORKSPACE_ID,
		occurredAt: LocalDateTime = OCCURRED_AT,
		amount: BigDecimal = BigDecimal("100.0000"),
	) = CreateOperationCommand(workspaceId, occurredAt, amount)

	private fun count(table: String) =
		jdbc.sql("SELECT count(*) FROM $table").query(Int::class.java).single()

	private fun countIn(workspaceId: UUID) = jdbc
		.sql("SELECT count(*) FROM t_operations WHERE workspace_id = :id")
		.param("id", workspaceId)
		.query(Int::class.java).single()

	private fun event(): EventRow = jdbc
		.sql(
			"""
			SELECT aggregate_type, aggregate_id, event_type, payload, occurred_at, recorded_at
			FROM t_events ORDER BY id
			"""
		)
		.query { rs, _ ->
			EventRow(
				entityType = rs.getString("aggregate_type"),
				entityId = rs.getObject("aggregate_id", UUID::class.java),
				eventType = rs.getString("event_type"),
				payload = rs.getString("payload"),
				occurredAt = rs.getObject("occurred_at", LocalDateTime::class.java),
				recordedAt = rs.getObject("recorded_at", LocalDateTime::class.java),
			)
		}
		.single()

	private fun operation(): OperationRow = jdbc
		.sql("SELECT id, workspace_id, amount, occurred_at, recorded_at FROM t_operations ORDER BY occurred_at")
		.query { rs, _ ->
			OperationRow(
				id = rs.getObject("id", UUID::class.java),
				workspaceId = rs.getObject("workspace_id", UUID::class.java),
				amount = rs.getBigDecimal("amount"),
				occurredAt = rs.getObject("occurred_at", LocalDateTime::class.java),
				recordedAt = rs.getObject("recorded_at", LocalDateTime::class.java),
			)
		}
		.single()

	private fun payload(): EventPayload =
		assertNotNull(mapper.readValue(event().payload, EventPayload::class.java))

	private data class EventRow(
		val entityType: String,
		val entityId: UUID,
		val eventType: String,
		val payload: String,
		val occurredAt: LocalDateTime,
		val recordedAt: LocalDateTime,
	)

	private data class OperationRow(
		val id: UUID,
		val workspaceId: UUID,
		val amount: BigDecimal,
		val occurredAt: LocalDateTime,
		val recordedAt: LocalDateTime,
	)

	private companion object {
		val WORKSPACE_ID: UUID = UUID.fromString("0199a1c2-3d4e-7f80-8123-000000000001")
		val OCCURRED_AT: LocalDateTime = LocalDateTime.parse("2026-03-15T14:30:00")
		val RECORDED_AT: LocalDateTime = LocalDateTime.parse("2026-03-16T09:00:00")
	}
}
