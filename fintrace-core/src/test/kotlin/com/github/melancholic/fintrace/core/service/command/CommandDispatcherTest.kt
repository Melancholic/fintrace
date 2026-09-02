package com.github.melancholic.fintrace.core.service.command

import com.github.melancholic.fintrace.core.domain.command.CancelOperationCommand
import com.github.melancholic.fintrace.core.domain.command.CreateOperationCommand
import com.github.melancholic.fintrace.core.service.command.handler.CommandHandler
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Routing only — no Spring, no database. */
class CommandDispatcherTest {

	private class RecordingHandler(
		private val result: UUID,
	) : CommandHandler<CreateOperationCommand, UUID> {
		override val commandType: KClass<out CreateOperationCommand> = CreateOperationCommand::class
		var received: CreateOperationCommand? = null

		override fun handle(command: CreateOperationCommand): UUID {
			received = command
			return result
		}
	}

	@Test
	fun `routes a command to the handler declaring its type`() {
		val expected = UUID.randomUUID()
		val handler = RecordingHandler(expected)
		val dispatcher = CommandDispatcherImpl(listOf(handler))
		val command = createOperation()

		val result = dispatcher.dispatch(command)

		assertEquals(expected, result)
		assertEquals(command, handler.received, "the handler received the original command")
	}

	@Test
	fun `fails when no handler is registered for the command`() {
		val dispatcher = CommandDispatcherImpl(listOf(RecordingHandler(UUID.randomUUID())))

		val failure = assertFailsWith<IllegalArgumentException> {
			dispatcher.dispatch(CancelOperationCommand(UUID.randomUUID(), LocalDateTime.now()))
		}

		assertEquals(true, failure.message?.contains("CancelOperationCommand"), failure.message)
	}

	@Test
	fun `fails when no handlers are registered at all`() {
		val dispatcher = CommandDispatcherImpl(emptyList())

		assertFailsWith<IllegalArgumentException> { dispatcher.dispatch(createOperation()) }
	}

	private fun createOperation() = CreateOperationCommand(
		workspaceId = UUID.randomUUID(),
		occurredAt = LocalDateTime.parse("2026-03-15T14:30:00"),
		amount = BigDecimal("100.0000"),
	)
}
