package com.github.melancholic.fintrace.core.validation

import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.command.CancelOperationCommand
import com.github.melancholic.fintrace.core.domain.command.CreateOperationCommand
import com.github.melancholic.fintrace.core.domain.command.ReviseOperationCommand
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.exception.ValidationError
import com.github.melancholic.fintrace.core.util.TimestampProvider
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Command-time invariants (§4.10). These run *before* an event is appended, which is the only
 * moment a command can still be rejected — an event has already happened.
 *
 * No Spring and no database: the rules are pure decisions over a command plus one existence
 * lookup, and that is what the fakes below stand in for.
 */
class OperationValidationServiceTest {

	private class FakeProjectionDAO(private val known: Set<Pair<UUID, UUID>>) : OperationProjectionDAO {
		val asked = mutableListOf<Pair<UUID, UUID>>()

		override fun exists(workspaceId: UUID, operationId: UUID): Boolean {
			asked += workspaceId to operationId
			return workspaceId to operationId in known
		}

		override fun createOrUpdate(projection: OperationProjection): UUID = unsupported()
		override fun getById(workspaceId: UUID, operationId: UUID): OperationProjection = unsupported()
		override fun removeAll(workspaceId: UUID): Unit = unsupported()
		override fun remove(workspaceId: UUID, id: UUID): Unit = unsupported()

		private fun unsupported(): Nothing =
			throw UnsupportedOperationException("validation reads existence only")
	}

	private fun service(known: Set<Pair<UUID, UUID>> = emptySet(), dao: FakeProjectionDAO = FakeProjectionDAO(known)) =
		dao to OperationValidationServiceImpl(dao, object : TimestampProvider {
			override fun now(): LocalDateTime = NOW
		})

	@Test
	fun `accepts a back-dated creation`() {
		val (_, validation) = service()

		validation.validate(create(occurredAt = LocalDateTime.parse("2001-09-11T08:46:00")))
	}

	@Test
	fun `accepts a creation dated exactly now`() {
		val (_, validation) = service()

		// The boundary is worth pinning: "now" is not the future, and a client clock that
		// agrees with the server to the second must not be rejected.
		validation.validate(create(occurredAt = NOW))
	}

	@Test
	fun `rejects a creation dated in the future`() {
		val (_, validation) = service()

		assertFailsWith<ValidationError> { validation.validate(create(occurredAt = NOW.plusSeconds(1))) }
	}

	@Test
	fun `accepts a revision of an operation that exists in the workspace`() {
		val (_, validation) = service(known = setOf(WORKSPACE to OPERATION))

		validation.validate(revise())
	}

	@Test
	fun `rejects a revision of an unknown operation`() {
		val (_, validation) = service()

		assertFailsWith<NotFoundEntityException> { validation.validate(revise()) }
	}

	@Test
	fun `looks the operation up within the command's workspace`() {
		val (dao, validation) = service(known = setOf(WORKSPACE to OPERATION))

		assertFailsWith<NotFoundEntityException> {
			validation.validate(revise(workspaceId = OTHER_WORKSPACE))
		}

		// The same id in another workspace must not resolve — the lookup is scoped by both keys.
		assertEquals(listOf(OTHER_WORKSPACE to OPERATION), dao.asked)
	}

	@Test
	fun `rejects a revision dated in the future`() {
		val (_, validation) = service(known = setOf(WORKSPACE to OPERATION))

		assertFailsWith<ValidationError> { validation.validate(revise(occurredAt = NOW.plusDays(1))) }
	}

	@Test
	fun `reports an unknown operation as missing rather than as a bad date`() {
		val (_, validation) = service()

		// Both rules fail here. Existence wins, so a caller aiming at something they cannot see
		// learns nothing about it from the status.
		assertFailsWith<NotFoundEntityException> { validation.validate(revise(occurredAt = NOW.plusDays(1))) }
	}

	@Test
	fun `accepts a cancellation of an operation that exists`() {
		val (_, validation) = service(known = setOf(WORKSPACE to OPERATION))

		validation.validate(cancel())
	}

	@Test
	fun `rejects a cancellation of an unknown operation`() {
		val (_, validation) = service()

		// This is also what makes cancelling twice a 404: the row is gone after the first.
		assertFailsWith<NotFoundEntityException> { validation.validate(cancel()) }
	}

	private fun create(occurredAt: LocalDateTime = OCCURRED_AT) =
		CreateOperationCommand(workspaceId = WORKSPACE, occurredAt = occurredAt, amount = AMOUNT)

	private fun revise(
		workspaceId: UUID = WORKSPACE,
		occurredAt: LocalDateTime = OCCURRED_AT,
	) = ReviseOperationCommand(
		workspaceId = workspaceId, operationId = OPERATION, occurredAt = occurredAt, amount = AMOUNT,
	)

	private fun cancel(workspaceId: UUID = WORKSPACE, occurredAt: LocalDateTime = OCCURRED_AT) =
		CancelOperationCommand(workspaceId = workspaceId, operationId = OPERATION, occurredAt = occurredAt)

	private companion object {
		val WORKSPACE: UUID = UUID.fromString("0199a1c2-3d4e-7f80-8123-000000000001")
		val OTHER_WORKSPACE: UUID = UUID.fromString("0199a1c2-3d4e-7f80-8123-000000000002")
		val OPERATION: UUID = UUID.fromString("0199a1c2-3d4e-7f80-8123-456789abcdef")
		val NOW: LocalDateTime = LocalDateTime.parse("2026-03-16T09:00:00")
		val OCCURRED_AT: LocalDateTime = LocalDateTime.parse("2026-03-15T14:30:00")
		val AMOUNT: BigDecimal = BigDecimal("-1234.5600")
	}
}
