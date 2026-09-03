package com.github.melancholic.fintrace.core.service.command.handler.operation

import com.github.melancholic.fintrace.core.dao.EventsDAO
import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.command.CancelOperationCommand
import com.github.melancholic.fintrace.core.domain.command.CreateOperationCommand
import com.github.melancholic.fintrace.core.domain.command.ReviseOperationCommand
import com.github.melancholic.fintrace.core.domain.event.EntityType
import com.github.melancholic.fintrace.core.domain.event.Event
import com.github.melancholic.fintrace.core.domain.event.EventType
import com.github.melancholic.fintrace.core.domain.event.payload.EventPayload
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
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
		occurredAt = payload.occurredAt,
		recordedAt = payload.recordedAt,
	).also { registered += it }

	override fun loadAll(workspaceId: UUID): List<Event> = registered
}

internal class RecordingProjectionDAO : OperationProjectionDAO {
	val upserted = mutableListOf<OperationProjection>()
	val removed = mutableListOf<Pair<UUID, UUID>>()

	override fun createOrUpdate(projection: OperationProjection): UUID {
		upserted += projection
		return projection.id
	}

	override fun remove(workspaceId: UUID, id: UUID) {
		removed += workspaceId to id
	}

	override fun exists(workspaceId: UUID, operationId: UUID): Boolean = true
	override fun getById(workspaceId: UUID, operationId: UUID): OperationProjection =
		throw UnsupportedOperationException()

	override fun removeAll(workspaceId: UUID): Unit = throw UnsupportedOperationException()
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
