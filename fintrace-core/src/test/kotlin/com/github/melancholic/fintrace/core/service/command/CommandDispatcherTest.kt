package com.github.melancholic.fintrace.core.service.command

import com.github.melancholic.fintrace.core.domain.command.CancelOperationCommand
import com.github.melancholic.fintrace.core.domain.command.CreateOperationCommand
import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import com.github.melancholic.fintrace.core.domain.event.payload.EventPayload
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.service.command.handler.CommandHandler
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Routing only — no Spring, no database. */
class CommandDispatcherTest {

	private class RecordingHandler(
		private val result: OperationProjection,
	) : CommandHandler<CreateOperationCommand, OperationProjection, EventPayload> {
		override val commandType: KClass<out CreateOperationCommand> = CreateOperationCommand::class
		var received: CreateOperationCommand? = null

		override fun handle(command: CreateOperationCommand): OperationProjection {
			received = command
			return result
		}
	}

	@Test
	fun `routes a command to the handler declaring its type`() {
		val expected = projection()
		val handler = RecordingHandler(expected)
		val dispatcher = CommandDispatcherImpl(listOf(handler))
		val command = createOperation()

		val result = dispatcher.dispatch(command)

		assertEquals(expected, result)
		assertEquals(command, handler.received, "the handler received the original command")
	}

	@Test
	fun `fails when no handler is registered for the command`() {
		val dispatcher = CommandDispatcherImpl(listOf(RecordingHandler(projection())))

		val failure = assertFailsWith<IllegalArgumentException> {
			dispatcher.dispatch(
				CancelOperationCommand(
					workspaceId = UUID.randomUUID(),
					operationId = UUID.randomUUID(),
				)
			)
		}

		assertEquals(true, failure.message?.contains("CancelOperationCommand"), failure.message)
	}

	@Test
	fun `fails when no handlers are registered at all`() {
		val dispatcher = CommandDispatcherImpl(emptyList())

		assertFailsWith<IllegalArgumentException> { dispatcher.dispatch(createOperation()) }
	}

	private fun projection() = OperationProjection(
		id = UUID.randomUUID(),
		workspaceId = UUID.randomUUID(),
		amount = BigDecimal("100.0000"),
        kind = OperationKind.EXPENSE,
        accountId = UUID.randomUUID(),
        categoryId = UUID.randomUUID(),
        transferId = null,
        counterpartId = null,
        comment = null,
        externalRef = null,
		occurredAt = LocalDateTime.parse("2026-03-15T14:30:00"),
		recordedAt = LocalDateTime.parse("2026-03-16T09:00:00"),
	)

	private fun createOperation() = CreateOperationCommand(
		workspaceId = UUID.randomUUID(),
		occurredAt = LocalDateTime.parse("2026-03-15T14:30:00"),
		amount = BigDecimal("100.0000"),
        accountId = UUID.randomUUID(),
        kind = OperationKind.EXPENSE,
        categoryId = UUID.randomUUID(),
        comment = null,
	)
}
