package com.github.melancholic.fintrace.core.service.command.handler.operation

import com.github.melancholic.fintrace.core.domain.command.ReviseOperationCommand
import com.github.melancholic.fintrace.core.domain.event.EntityType
import com.github.melancholic.fintrace.core.domain.event.EventType
import com.github.melancholic.fintrace.core.domain.event.payload.OperationRevisedV1
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.service.command.handler.operation.HandlerFixtures.OCCURRED_AT
import com.github.melancholic.fintrace.core.service.command.handler.operation.HandlerFixtures.OPERATION
import com.github.melancholic.fintrace.core.service.command.handler.operation.HandlerFixtures.RECORDED_AT
import com.github.melancholic.fintrace.core.service.command.handler.operation.HandlerFixtures.WORKSPACE
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The revise handler in isolation: what it writes, in what order, and from where. */
class ReviseOperationCommandHandlerTest {

	private val events = RecordingEventsDAO()
	private val projections = RecordingProjectionDAO()
	private val validation = RecordingValidation()

	private fun handler(validation: RecordingValidation = this.validation) = ReviseOperationCommandHandler(
		timestampProvider = FixedTimestampProvider(RECORDED_AT),
		operationProjectionDAO = projections,
		validationService = validation,
		eventsDAO = events,
	)

	@Test
	fun `appends one event and upserts one row`() {
		handler().handle(command())

		assertEquals(1, events.registered.size)
		assertEquals(1, projections.upserted.size)
		assertTrue(projections.removed.isEmpty(), "a revision removes nothing")
	}

	@Test
	fun `classifies the event as a revision of the operation named by the command`() {
		handler().handle(command())

		val event = events.registered.single()
		assertEquals(EntityType.OPERATION, event.entityType)
		assertEquals(EventType.REVISED, event.eventType)
		// The aggregate is the operation being revised, never a fresh id: only creates mint ids.
		assertEquals(OPERATION, event.entityId)
		assertEquals(WORKSPACE, event.workspaceId)
	}

	@Test
	fun `carries the complete new state, not the changed fields`() {
		handler().handle(command(amount = BigDecimal("42.0000")))

		val payload = events.registered.single().payload as OperationRevisedV1
		assertEquals(OPERATION, payload.id)
		assertEquals(WORKSPACE, payload.workspaceId)
		assertEquals(BigDecimal("42.0000"), payload.amount)
		assertEquals(OCCURRED_AT, payload.occurredAt)
		assertEquals(1, payload.version)
	}

	@Test
	fun `takes occurred_at from the command and recorded_at from the clock`() {
		val moved = LocalDateTime.parse("2019-07-04T12:00:00")

		handler().handle(command(occurredAt = moved))

		val payload = events.registered.single().payload as OperationRevisedV1
		// A revision may correct the business date; when it entered the system is the server's.
		assertEquals(moved, payload.occurredAt)
		assertEquals(RECORDED_AT, payload.recordedAt)
	}

	@Test
	fun `writes the projection from the payload`() {
		handler().handle(command(amount = BigDecimal("42.0000")))

		val payload = events.registered.single().payload as OperationRevisedV1
		assertEquals(payload.asProjection(), projections.upserted.single())
	}

	@Test
	fun `validates before appending anything`() {
		val rejecting = RecordingValidation(NotFoundEntityException("no such operation"))

		assertFailsWith<NotFoundEntityException> { handler(rejecting).handle(command()) }

		// §4.10: an event has already happened and cannot be rejected, so an invalid command has
		// to be stopped before the append — not compensated afterwards.
		assertEquals(1, rejecting.calls)
		assertTrue(events.registered.isEmpty(), "no event may be written for a rejected command")
		assertTrue(projections.upserted.isEmpty(), "no projection may be written either")
	}

	private fun command(
		amount: BigDecimal = BigDecimal("-1234.5600"),
		occurredAt: LocalDateTime = OCCURRED_AT,
	) = ReviseOperationCommand(
		workspaceId = WORKSPACE, operationId = OPERATION, occurredAt = occurredAt, amount = amount,
	)
}
