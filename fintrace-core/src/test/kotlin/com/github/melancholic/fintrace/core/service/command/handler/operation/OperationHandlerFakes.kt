package com.github.melancholic.fintrace.core.service.command.handler.operation

import com.github.melancholic.fintrace.core.dao.EventsDAO
import com.github.melancholic.fintrace.core.domain.command.CancelOperationCommand
import com.github.melancholic.fintrace.core.domain.command.CreateOperationCommand
import com.github.melancholic.fintrace.core.domain.command.ReviseOperationCommand
import com.github.melancholic.fintrace.core.domain.event.EntityType
import com.github.melancholic.fintrace.core.domain.event.Event
import com.github.melancholic.fintrace.core.domain.event.EventType
import com.github.melancholic.fintrace.core.domain.event.payload.EventPayload
import com.github.melancholic.fintrace.core.domain.event.payload.TemporalEventPayload
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.domain.projection.Projection
import com.github.melancholic.fintrace.core.service.projection.ProjectionApplier
import com.github.melancholic.fintrace.core.service.projection.ProjectionChange
import com.github.melancholic.fintrace.core.util.TimestampProvider
import com.github.melancholic.fintrace.core.validation.OperationValidationService
import java.time.LocalDateTime
import java.util.UUID

/**
 * Fakes shared by the operation handler tests.
 *
 * They record rather than verify, so a test can assert not only *what* a handler did but in what
 * order — which is the property that matters here: validation has to happen before the event is
 * appended (§4.10), and a mock that only counts calls cannot tell you that.
 */
internal class RecordingEventsDAO : EventsDAO {
	val registered = mutableListOf<Event>()

	override fun registerEvent(
		workspaceId: UUID,
		entityType: EntityType,
		eventType: EventType,
		payload: EventPayload,
	): Event = Event(
		id = registered.size + 1L,
		workspaceId = workspaceId,
		entityType = entityType,
		entityId = payload.id,
		eventType = eventType,
		payload = payload,
		// Mirrors EventsDAOImpl: an event with no business date is dated when it was recorded.
		occurredAt = (payload as? TemporalEventPayload)?.occurredAt ?: payload.recordedAt,
		recordedAt = payload.recordedAt,
	).also { registered += it }

	override fun loadAll(workspaceId: UUID): List<Event> = registered
}

/** Records the changes a handler applies, without any of the SQL behind them. */
internal class RecordingProjectionApplier : ProjectionApplier {
	val applied = mutableListOf<ProjectionChange>()
	var cleared = 0

	override fun apply(change: ProjectionChange) {
		applied += change
	}

	override fun clear(workspaceId: UUID) {
		cleared++
	}

	val upsertedRows: List<Projection>
		get() = applied.filterIsInstance<ProjectionChange.Upsert>().flatMap { it.rows }

	val operations: List<OperationProjection>
		get() = upsertedRows.filterIsInstance<OperationProjection>()

	val removals: List<ProjectionChange.Remove>
		get() = applied.filterIsInstance<ProjectionChange.Remove>()
}

/** Rejects on demand, so a test can prove nothing is written when a command is invalid. */
internal class RecordingValidation(private val failure: RuntimeException? = null) : OperationValidationService {
	var calls = 0

	override fun validate(operation: CreateOperationCommand) = record()
	override fun validate(operation: ReviseOperationCommand) = record()
	override fun validate(operation: CancelOperationCommand) = record()

	private fun record() {
		calls++
		failure?.let { throw it }
	}
}

internal class FixedTimestampProvider(private val now: LocalDateTime) : TimestampProvider {
	override fun now(): LocalDateTime = now
}

internal object HandlerFixtures {
	val WORKSPACE: UUID = UUID.fromString("0199a1c2-3d4e-7f80-8123-000000000001")
	val OPERATION: UUID = UUID.fromString("0199a1c2-3d4e-7f80-8123-456789abcdef")
	val OCCURRED_AT: LocalDateTime = LocalDateTime.parse("2026-03-15T14:30:00")
	val RECORDED_AT: LocalDateTime = LocalDateTime.parse("2026-03-16T09:00:00")
}
