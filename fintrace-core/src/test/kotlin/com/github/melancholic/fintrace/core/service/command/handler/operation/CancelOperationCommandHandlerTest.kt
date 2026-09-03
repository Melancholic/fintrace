package com.github.melancholic.fintrace.core.service.command.handler.operation

import com.github.melancholic.fintrace.core.domain.command.CancelOperationCommand
import com.github.melancholic.fintrace.core.domain.event.EntityType
import com.github.melancholic.fintrace.core.domain.event.EventType
import com.github.melancholic.fintrace.core.domain.event.payload.OperationCanceledV1
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.service.command.handler.operation.HandlerFixtures.OCCURRED_AT
import com.github.melancholic.fintrace.core.service.command.handler.operation.HandlerFixtures.OPERATION
import com.github.melancholic.fintrace.core.service.command.handler.operation.HandlerFixtures.RECORDED_AT
import com.github.melancholic.fintrace.core.service.command.handler.operation.HandlerFixtures.WORKSPACE
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The cancel handler in isolation. Its projection effect is a removal, not a row. */
class CancelOperationCommandHandlerTest {

	private val events = RecordingEventsDAO()
	private val projections = RecordingProjectionDAO()
	private val validation = RecordingValidation()

	private fun handler(validation: RecordingValidation = this.validation) = CancelOperationCommandHandler(
		timestampProvider = FixedTimestampProvider(RECORDED_AT),
		operationProjectionDAO = projections,
		validationService = validation,
		eventsDAO = events,
	)

	@Test
	fun `appends one event and removes one row`() {
		handler().handle(command())

		assertEquals(1, events.registered.size)
		assertEquals(listOf(WORKSPACE to OPERATION), projections.removed)
		assertTrue(projections.upserted.isEmpty(), "a cancellation writes no row")
	}

	@Test
	fun `classifies the event as a cancellation of the operation named by the command`() {
		handler().handle(command())

		val event = events.registered.single()
		assertEquals(EntityType.OPERATION, event.entityType)
		assertEquals(EventType.CANCELLED, event.eventType)
		// The event must name the operation it cancels; a fresh id would leave the row in place
		// and the log pointing at an aggregate that never existed.
		assertEquals(OPERATION, event.entityId)
	}

	@Test
	fun `records the cancellation without restating the operation's state`() {
		handler().handle(command())

		val payload = events.registered.single().payload as OperationCanceledV1
		// The resulting state of a cancellation is "gone", so the payload identifies rather than
		// describes; the previous event still holds what was cancelled.
		assertEquals(OPERATION, payload.id)
		assertEquals(WORKSPACE, payload.workspaceId)
		assertEquals(RECORDED_AT, payload.recordedAt)
		assertEquals(1, payload.version)
	}

	@Test
	fun `removes the row the event names`() {
		handler().handle(command())

		val event = events.registered.single()
		assertEquals(listOf(event.workspaceId to event.entityId), projections.removed)
	}

	@Test
	fun `validates before appending anything`() {
		val rejecting = RecordingValidation(NotFoundEntityException("no such operation"))

		assertFailsWith<NotFoundEntityException> { handler(rejecting).handle(command()) }

		// Cancelling twice arrives here: the row is already gone, so validation rejects and the
		// log gains nothing.
		assertEquals(1, rejecting.calls)
		assertTrue(events.registered.isEmpty())
		assertTrue(projections.removed.isEmpty())
	}

	private fun command() = CancelOperationCommand(
		workspaceId = WORKSPACE, operationId = OPERATION, occurredAt = OCCURRED_AT,
	)
}
