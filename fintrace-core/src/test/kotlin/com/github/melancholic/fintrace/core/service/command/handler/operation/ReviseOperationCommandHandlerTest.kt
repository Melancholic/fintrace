package com.github.melancholic.fintrace.core.service.command.handler.operation

import com.github.melancholic.fintrace.core.domain.command.ReviseOperationCommand
import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import com.github.melancholic.fintrace.core.domain.event.EntityType
import com.github.melancholic.fintrace.core.domain.event.EventType
import com.github.melancholic.fintrace.core.domain.event.payload.OperationCreatedV1
import com.github.melancholic.fintrace.core.domain.event.payload.OperationRevisedV1
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.service.command.handler.operation.HandlerFixtures.ACCOUNT
import com.github.melancholic.fintrace.core.service.command.handler.operation.HandlerFixtures.CATEGORY
import com.github.melancholic.fintrace.core.service.command.handler.operation.HandlerFixtures.OCCURRED_AT
import com.github.melancholic.fintrace.core.service.command.handler.operation.HandlerFixtures.OPERATION
import com.github.melancholic.fintrace.core.service.command.handler.operation.HandlerFixtures.RECORDED_AT
import com.github.melancholic.fintrace.core.service.command.handler.operation.HandlerFixtures.WORKSPACE
import com.github.melancholic.fintrace.core.service.projection.ProjectionChange
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The revise handler in isolation: what it writes, in what order, and from where. */
class ReviseOperationCommandHandlerTest {

	private val events = RecordingEventsDAO()
	private val projections = RecordingProjectionApplier()
	private val validation = RecordingValidation()

	private fun handler(validation: RecordingValidation = this.validation) = ReviseOperationCommandHandler(
		timestampProvider = FixedTimestampProvider(RECORDED_AT),
		projectionApplier = projections,
		validationService = validation,
		eventsDAO = events,
	)

	@Test
	fun `appends one event and upserts one row`() {
        seedCreated()

		handler().handle(command())

        assertEquals(2, events.registered.size, "the seeded creation, plus one revision")
		assertEquals(1, projections.operations.size)
		assertTrue(projections.removals.isEmpty(), "a revision removes nothing")
	}

	@Test
	fun `classifies the event as a revision of the operation named by the command`() {
        seedCreated()

		handler().handle(command())

        val event = events.registered.last()
		assertEquals(EntityType.OPERATION, event.entityType)
		assertEquals(EventType.REVISED, event.eventType)
		// The aggregate is the operation being revised, never a fresh id: only creates mint ids.
		assertEquals(OPERATION, event.entityId)
		assertEquals(WORKSPACE, event.workspaceId)
	}

	@Test
	fun `carries the complete new state, not the changed fields`() {
        seedCreated()

		handler().handle(command(amount = BigDecimal("42.0000")))

        val payload = events.registered.last().payload as OperationRevisedV1
		assertEquals(OPERATION, payload.id)
		assertEquals(WORKSPACE, payload.workspaceId)
        // The command carries a magnitude; the sign belongs to the stored fact (§4.13).
        assertEquals(BigDecimal("-42.0000"), payload.amount)
        assertEquals(OperationKind.EXPENSE, payload.kind)
        assertEquals(ACCOUNT, payload.accountId)
        assertEquals(CATEGORY, payload.categoryId)
		assertEquals(OCCURRED_AT, payload.occurredAt)
		assertEquals(1, payload.version)
	}

    @Test
    fun `carries forward the fields the command cannot express`() {
        val transfer = UUID.fromString("0199a1c2-3d4e-7f80-8123-00000000cccc")
        val counterpart = UUID.fromString("0199a1c2-3d4e-7f80-8123-00000000dddd")
        seedCreated(externalRef = "mok:4711", transferId = transfer, counterpartId = counterpart)

        handler().handle(command())

        // A payload carries full state, and a REVISED event that dropped these would be a lie
        // about what the operation now is: the row rebuilt from it would lose its link to the
        // source dump, and — from 1.18 — to the other half of its transfer.
        val payload = events.registered.last().payload as OperationRevisedV1
        assertEquals("mok:4711", payload.externalRef, "the import link survives an edit")
        assertEquals(transfer, payload.transferId)
        assertEquals(counterpart, payload.counterpartId)
    }

    @Test
    fun `an income keeps its amount positive`() {
        seedCreated()

        handler().handle(command(amount = BigDecimal("42.0000"), kind = OperationKind.INCOME))

        val payload = events.registered.last().payload as OperationRevisedV1
        assertEquals(BigDecimal("42.0000"), payload.amount)
    }

	@Test
	fun `takes occurred_at from the command and recorded_at from the clock`() {
		val moved = LocalDateTime.parse("2019-07-04T12:00:00")
        seedCreated()

		handler().handle(command(occurredAt = moved))

        val payload = events.registered.last().payload as OperationRevisedV1
		// A revision may correct the business date; when it entered the system is the server's.
		assertEquals(moved, payload.occurredAt)
		assertEquals(RECORDED_AT, payload.recordedAt)
	}

	@Test
	fun `writes the projection from the payload`() {
        seedCreated()

		handler().handle(command(amount = BigDecimal("42.0000")))

        val payload = events.registered.last().payload as OperationRevisedV1
		assertEquals(
			(payload.projectionChange() as ProjectionChange.Upsert).rows.single(),
			projections.upsertedRows.single(),
		)
	}

	@Test
	fun `validates before appending anything`() {
		val rejecting = RecordingValidation(NotFoundEntityException("no such operation"))

		assertFailsWith<NotFoundEntityException> { handler(rejecting).handle(command()) }

		// §4.10: an event has already happened and cannot be rejected, so an invalid command has
		// to be stopped before the append — not compensated afterwards.
		assertEquals(1, rejecting.calls)
		assertTrue(events.registered.isEmpty(), "no event may be written for a rejected command")
		assertTrue(projections.applied.isEmpty(), "no projection may be written either")
	}

	private fun command(
        amount: BigDecimal = BigDecimal("1234.5600"),
		occurredAt: LocalDateTime = OCCURRED_AT,
        kind: OperationKind = OperationKind.EXPENSE,
	) = ReviseOperationCommand(
        workspaceId = WORKSPACE,
        operationId = OPERATION,
        occurredAt = occurredAt,
        amount = amount,
        accountId = ACCOUNT,
        kind = kind,
        categoryId = CATEGORY,
        comment = null,
    )

    /**
     * A revision always follows a creation, and the handler reads that event for the fields the
     * command cannot carry. Without it the handler has nothing to revise.
     */
    private fun seedCreated(
        externalRef: String? = "mok:4711",
        transferId: UUID? = null,
        counterpartId: UUID? = null,
    ) = events.registerEvent(
        WORKSPACE, EntityType.OPERATION, EventType.CREATED,
        OperationCreatedV1(
            id = OPERATION,
            workspaceId = WORKSPACE,
            amount = BigDecimal("-10.0000"),
            occurredAt = OCCURRED_AT,
            recordedAt = RECORDED_AT,
            accountId = ACCOUNT,
            kind = OperationKind.EXPENSE,
            categoryId = CATEGORY,
            transferId = transferId,
            counterpartId = counterpartId,
            externalRef = externalRef,
            comment = null,
        )
	)
}
