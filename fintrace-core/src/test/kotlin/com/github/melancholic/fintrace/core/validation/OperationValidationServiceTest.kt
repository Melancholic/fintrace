package com.github.melancholic.fintrace.core.validation

import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.CategoryProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.command.CancelOperationCommand
import com.github.melancholic.fintrace.core.domain.command.CreateOperationCommand
import com.github.melancholic.fintrace.core.domain.command.ReviseOperationCommand
import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import com.github.melancholic.fintrace.core.domain.entity.CategorySystemCode
import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import com.github.melancholic.fintrace.core.domain.projection.AccountProjection
import com.github.melancholic.fintrace.core.domain.projection.CategoryProjection
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.exception.ActionConflictException
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.exception.OperationNotSupported
import com.github.melancholic.fintrace.core.exception.ValidationError
import com.github.melancholic.fintrace.core.util.TimestampProvider
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Command-time invariants (§4.10). These run *before* an event is appended, which is the only
 * moment a command can still be rejected — an event has already happened.
 *
 * No Spring and no database: the rules are pure decisions over a command plus one existence
 * lookup, and that is what the fakes below stand in for.
 */
class OperationValidationServiceTest {

    /**
     * Existence and "is this a transfer leg" are one question now, so the fake answers with the row
     * rather than with a boolean — which is why the DAO has no `exists` any more.
     */
    private class FakeProjectionDAO(
        private val known: Set<Pair<UUID, UUID>>,
        private val transferId: UUID? = null,
    ) : OperationProjectionDAO {
		val asked = mutableListOf<Pair<UUID, UUID>>()

        override fun getByIdAsOptional(workspaceId: UUID, operationId: UUID): Optional<OperationProjection> {
			asked += workspaceId to operationId
            if (workspaceId to operationId !in known) return Optional.empty()
            return Optional.of(
                OperationProjection(
                    id = operationId,
                    workspaceId = workspaceId,
                    amount = AMOUNT.negate(),
                    kind = if (transferId == null) OperationKind.EXPENSE else OperationKind.TRANSFER,
                    accountId = ACCOUNT,
                    categoryId = CATEGORY,
                    transferId = transferId,
                    counterpartId = if (transferId == null) null else COUNTERPART,
                    comment = null,
                    externalRef = null,
                    occurredAt = OCCURRED_AT,
                    recordedAt = NOW,
                )
            )
		}

        override fun getTransferPartiesById(workspaceId: UUID, transferId: UUID): Pair<UUID, UUID> = unsupported()
        override fun existsTransferById(workspaceId: UUID, transferId: UUID): Boolean = unsupported()
		override fun createOrUpdate(projection: OperationProjection): UUID = unsupported()
		override fun getById(workspaceId: UUID, operationId: UUID): OperationProjection = unsupported()
		override fun removeAll(workspaceId: UUID): Unit = unsupported()
		override fun remove(workspaceId: UUID, id: UUID): Unit = unsupported()
		override fun remove(workspaceId: UUID, ids: Set<UUID>): Unit = unsupported()

		private fun unsupported(): Nothing =
            throw UnsupportedOperationException("validation reads the row it is about to change")
	}

    /**
     * The validator reaches the account and category DAOs through the registry, so a unit test has
     * to stand one up. Both answer "exists" for the fixtures below and nothing else — the rules
     * under test are about the command, not about those tables.
     */
    private class FakeAccountDAO(private val known: Set<Pair<UUID, UUID>>, private val archived: Boolean = false) :
        AccountProjectionDAO {
        override fun exists(workspaceId: UUID, accountId: UUID) = workspaceId to accountId in known

        override fun getById(workspaceId: UUID, accountId: UUID): AccountProjection {
            if (workspaceId to accountId !in known) {
                throw NotFoundEntityException("no such account")
            }
            return AccountProjection(
                id = accountId,
                workspaceId = workspaceId,
                name = "account",
                currency = "EUR",
                archived = archived,
                icon = null,
                recordedAt = NOW,
            )
        }

        override fun createOrUpdate(projection: AccountProjection): UUID = unsupported()
        override fun getAllAccounts(workspaceId: UUID, includeArchived: Boolean): List<AccountProjection> =
            unsupported()

        override fun removeAll(workspaceId: UUID): Unit = unsupported()
        override fun remove(workspaceId: UUID, accountId: UUID): Unit = unsupported()
        override fun remove(workspaceId: UUID, ids: Set<UUID>): Unit = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException("validation reads existence only")
    }

    private class FakeCategoryDAO(private val known: Set<Pair<UUID, UUID>>, private val archived: Boolean = false) :
        CategoryProjectionDAO {
        override fun getById(workspaceId: UUID, categoryId: UUID): CategoryProjection {
            if (workspaceId to categoryId !in known) {
                throw NotFoundEntityException("no such category")
            }
            return CategoryProjection(
                id = categoryId,
                workspaceId = workspaceId,
                parentId = null,
                name = "category",
                kind = CategoryKind.EXPENSE,
                archived = archived,
                systemCode = null,
                icon = null,
                recordedAt = NOW,
            )
        }

        override fun createOrUpdate(projection: CategoryProjection): UUID = unsupported()
        override fun getByIdAsOptional(workspaceId: UUID, categoryId: UUID): Optional<CategoryProjection> =
            unsupported()

        override fun getAllCategories(workspaceId: UUID, includeArchived: Boolean): List<CategoryProjection> =
            unsupported()

        override fun findSubtreeIds(workspaceId: UUID, categoryId: UUID): List<UUID> = unsupported()
        override fun getFallbackCategory(workspaceId: UUID, categoryKind: CategoryKind): CategoryProjection =
            unsupported()

        override fun getBySystemCode(workspaceId: UUID, systemCode: CategorySystemCode): CategoryProjection =
            unsupported()

        override fun removeAll(workspaceId: UUID): Unit = unsupported()
        override fun remove(workspaceId: UUID, categoryId: UUID): Unit = unsupported()
        override fun remove(workspaceId: UUID, ids: Set<UUID>): Unit = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException("validation reads existence only")
    }

    private fun service(
        known: Set<Pair<UUID, UUID>> = emptySet(),
        transferId: UUID? = null,
        dao: FakeProjectionDAO = FakeProjectionDAO(known, transferId),
        knownAccounts: Set<Pair<UUID, UUID>> = setOf(WORKSPACE to ACCOUNT, OTHER_WORKSPACE to ACCOUNT),
        knownCategories: Set<Pair<UUID, UUID>> = setOf(WORKSPACE to CATEGORY, OTHER_WORKSPACE to CATEGORY),
        accountArchived: Boolean = false,
        categoryArchived: Boolean = false,
    ) = dao to OperationValidationServiceImpl(
        dao,
        object : TimestampProvider {
			override fun now(): LocalDateTime = NOW
        },
        FakeAccountDAO(knownAccounts, accountArchived),
        FakeCategoryDAO(knownCategories, categoryArchived),
    )

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

    // ------------------------------------------------------------------ transfer legs (1.19)

    @Test
    fun `rejects a revision of a transfer leg`() {
        val (_, validation) = service(known = setOf(WORKSPACE to OPERATION), transferId = TRANSFER)

        // Revising a leg here would rewrite one half of a pair — worse, a kind of INCOME would turn
        // it into an ordinary operation while its counterpart still points at it (§10.3). The pair
        // is only writable through /transfers.
        assertFailsWith<OperationNotSupported> { validation.validate(revise()) }
    }

    @Test
    fun `rejects a cancellation of a transfer leg`() {
        val (_, validation) = service(known = setOf(WORKSPACE to OPERATION), transferId = TRANSFER)

        // Cancelling one leg leaves the other with a counterpart_id pointing at a row that no
        // longer exists, and a rebuild reproduces that faithfully.
        assertFailsWith<OperationNotSupported> { validation.validate(cancel()) }
    }

    @Test
    fun `refuses a transfer leg before it looks at anything else`() {
        val (_, validation) = service(known = setOf(WORKSPACE to OPERATION), transferId = TRANSFER)

        // Both rules fail on this command. The leg guard has to win, or a client fixing the date
        // would be told the request is nearly right when the endpoint is simply the wrong one.
        assertFailsWith<OperationNotSupported> { validation.validate(revise(occurredAt = NOW.plusDays(1))) }
    }

    @Test
    fun `rejects a revision that carries the transfer kind`() {
        val (_, validation) = service(known = setOf(WORKSPACE to OPERATION))

        // The same rule as on create: an ordinary operation cannot be turned into a leg either,
        // since a PUT here can name no counterpart. Checked explicitly rather than left to
        // `asCategoryKind` to throw further down, which would depend on a category lookup running.
        assertFailsWith<OperationNotSupported> {
            validation.validate(revise(kind = OperationKind.TRANSFER))
        }
    }

    @Test
    fun `names the transfer a leg belongs to`() {
        val (_, validation) = service(known = setOf(WORKSPACE to OPERATION), transferId = TRANSFER)

        // The client is holding a leg id and needs the transfer id to reach /transfers — telling it
        // only "not allowed here" leaves it with no way forward.
        val refusal = assertFailsWith<OperationNotSupported> { validation.validate(cancel()) }
        assertTrue(refusal.message!!.contains(TRANSFER.toString()), "the 409 names the transfer")
    }

    @Test
    fun `accepts a revision of an operation that is not a leg`() {
        val (_, validation) = service(known = setOf(WORKSPACE to OPERATION))

        // The guard keys off transfer_id alone, so an ordinary operation must pass untouched.
        validation.validate(revise())
        validation.validate(cancel())
    }

    // ------------------------------------------------------------------ amount (1.16)

    @Test
    fun `rejects a negative amount`() {
        val (_, validation) = service()

        // A command carries the magnitude; the kind decides the sign. A negative amount here is a
        // caller that believes otherwise, and accepting it would flip an expense into income.
        assertFailsWith<ValidationError> { validation.validate(create(amount = BigDecimal("-1.0000"))) }
    }

    @Test
    fun `rejects a zero amount`() {
        val (_, validation) = service()

        // An operation that moves nothing is not an operation.
        assertFailsWith<ValidationError> { validation.validate(create(amount = BigDecimal.ZERO)) }
    }

    // ------------------------------------------------------------------ account (1.16)

    @Test
    fun `rejects an operation on an account that does not exist`() {
        val (_, validation) = service(knownAccounts = emptySet())

        assertFailsWith<NotFoundEntityException> { validation.validate(create()) }
    }

    @Test
    fun `rejects an operation on an account in another workspace`() {
        val (_, validation) = service(knownAccounts = setOf(OTHER_WORKSPACE to ACCOUNT))

        // The lookup is workspace-scoped: an account someone else owns must be as invisible as
        // one that was never created.
        assertFailsWith<NotFoundEntityException> { validation.validate(create()) }
    }

    @Test
    fun `rejects an operation on an archived account`() {
        val (_, validation) = service(accountArchived = true)

        // §4.8: archiving is what stands in for deleting an account, so it has to close the
        // write path — otherwise "archived" means nothing.
        assertFailsWith<ActionConflictException> { validation.validate(create()) }
    }

    // ------------------------------------------------------------------ category (1.16)

    @Test
    fun `rejects an operation filed under a category that does not exist`() {
        val (_, validation) = service(knownCategories = emptySet())

        assertFailsWith<NotFoundEntityException> { validation.validate(create()) }
    }

    @Test
    fun `rejects an operation filed under a category in another workspace`() {
        val (_, validation) = service(knownCategories = setOf(OTHER_WORKSPACE to CATEGORY))

        assertFailsWith<NotFoundEntityException> { validation.validate(create()) }
    }

    @Test
    fun `rejects an operation filed under an archived category`() {
        val (_, validation) = service(categoryArchived = true)

        assertFailsWith<ActionConflictException> { validation.validate(create()) }
    }

    @Test
    fun `rejects an income filed under an expense category`() {
        val (_, validation) = service()

        // The fake's categories are all EXPENSE. Filing income there would make the category tree
        // disagree with the ledger, and every breakdown built on it would double-count (§4.7).
        assertFailsWith<ActionConflictException> { validation.validate(create(kind = OperationKind.INCOME)) }
    }

    @Test
    fun `rejects a transfer written as an ordinary operation`() {
        val (_, validation) = service()

        // A transfer is a pair, and the pair is the invariant (§4.5). A leg made here would have
        // no counterpart, which no rebuild can repair — /transfers is the only way in.
        assertFailsWith<ActionConflictException> { validation.validate(create(kind = OperationKind.TRANSFER)) }
    }

    @Test
    fun `accepts a creation with no category at all`() {
        val (_, validation) = service(knownCategories = emptySet())

        // A null category is not a missing one: the handler resolves it to the branch's Others
        // (§5.1), so validation must not reject it — and must not go looking for it either, which
        // is what the empty known-set here proves.
        validation.validate(create(categoryId = null))
    }

    private fun create(
        occurredAt: LocalDateTime = OCCURRED_AT,
        amount: BigDecimal = AMOUNT,
        categoryId: UUID? = CATEGORY,
        kind: OperationKind = OperationKind.EXPENSE,
    ) = CreateOperationCommand(
        workspaceId = WORKSPACE,
        occurredAt = occurredAt,
        amount = amount,
        accountId = ACCOUNT,
        kind = kind,
        categoryId = categoryId,
        comment = null,
    )

	private fun revise(
		workspaceId: UUID = WORKSPACE,
		occurredAt: LocalDateTime = OCCURRED_AT,
        kind: OperationKind = OperationKind.EXPENSE,
	) = ReviseOperationCommand(
        workspaceId = workspaceId,
        operationId = OPERATION,
        occurredAt = occurredAt,
        amount = AMOUNT,
        accountId = ACCOUNT,
        kind = kind,
        categoryId = CATEGORY,
        comment = null,
	)

	private fun cancel(workspaceId: UUID = WORKSPACE) =
		CancelOperationCommand(workspaceId = workspaceId, operationId = OPERATION)

	private companion object {
		val WORKSPACE: UUID = UUID.fromString("0199a1c2-3d4e-7f80-8123-000000000001")
		val OTHER_WORKSPACE: UUID = UUID.fromString("0199a1c2-3d4e-7f80-8123-000000000002")
		val OPERATION: UUID = UUID.fromString("0199a1c2-3d4e-7f80-8123-456789abcdef")
		val NOW: LocalDateTime = LocalDateTime.parse("2026-03-16T09:00:00")
		val OCCURRED_AT: LocalDateTime = LocalDateTime.parse("2026-03-15T14:30:00")
        val ACCOUNT: UUID = UUID.fromString("0199a1c2-3d4e-7f80-8123-00000000aaaa")
        val CATEGORY: UUID = UUID.fromString("0199a1c2-3d4e-7f80-8123-00000000bbbb")
        val TRANSFER: UUID = UUID.fromString("0199a1c2-3d4e-7f80-8123-00000000cccc")
        val COUNTERPART: UUID = UUID.fromString("0199a1c2-3d4e-7f80-8123-00000000dddd")

        // A command carries the magnitude; the handler applies the sign (§4.13).
        val AMOUNT: BigDecimal = BigDecimal("1234.5600")
	}
}
