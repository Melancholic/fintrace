package com.github.melancholic.fintrace.core.validation

import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.domain.command.CreateAccountCommand
import com.github.melancholic.fintrace.core.domain.command.ReviseAccountCommand
import com.github.melancholic.fintrace.core.domain.command.SetAccountArchivedCommand
import com.github.melancholic.fintrace.core.domain.projection.AccountProjection
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.exception.ValidationError
import org.junit.jupiter.api.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Rules that hold whoever the caller is: no Spring, no HTTP, no database. */
class AccountValidationServiceTest {

    private class FakeAccountDAO(private val known: Set<Pair<UUID, UUID>>) : AccountProjectionDAO {
        val asked = mutableListOf<Pair<UUID, UUID>>()

        override fun exists(workspaceId: UUID, accountId: UUID): Boolean {
            asked += workspaceId to accountId
            return workspaceId to accountId in known
        }

        override fun createOrUpdate(projection: AccountProjection): UUID = unsupported()
        override fun getById(workspaceId: UUID, accountId: UUID): AccountProjection = unsupported()
        override fun getAllAccounts(workspaceId: UUID, includeArchived: Boolean): List<AccountProjection> =
            unsupported()

        override fun remove(workspaceId: UUID, accountId: UUID): Unit = unsupported()
        override fun remove(workspaceId: UUID, ids: Set<UUID>): Unit = unsupported()
        override fun removeAll(workspaceId: UUID): Unit = unsupported()

        private fun unsupported(): Nothing =
            throw UnsupportedOperationException("validation reads existence only")
    }

    private fun service(known: Set<Pair<UUID, UUID>> = emptySet(), dao: FakeAccountDAO = FakeAccountDAO(known)) =
        dao to AccountValidationServiceImpl(dao)

    @Test
    fun `accepts a well-formed account`() {
        val (_, validation) = service()

        validation.validate(CreateAccountCommand(WORKSPACE, "cash-eur", "EUR", icon = null))
    }

    @Test
    fun `rejects a blank name`() {
        val (_, validation) = service()

        assertFailsWith<ValidationError> {
            validation.validate(CreateAccountCommand(WORKSPACE, "   ", "EUR", icon = null))
        }
    }

    @Test
    fun `rejects a name outside the permitted pattern`() {
        val (_, validation) = service()

        listOf("bad name", "-leading", "semi;colon").forEach {
            assertFailsWith<ValidationError>("expected '$it' to be rejected") {
                validation.validate(CreateAccountCommand(WORKSPACE, it, "EUR", icon = null))
            }
        }
    }

    @Test
    fun `rejects a currency that is well-shaped but does not exist`() {
        val (_, validation) = service()

        // The reason the check is here and not only in the DTO: a regex cannot tell ZZZ from EUR.
        assertFailsWith<ValidationError> {
            validation.validate(CreateAccountCommand(WORKSPACE, "cash", "ZZZ", icon = null))
        }
    }

    @Test
    fun `rejects a currency of the wrong shape`() {
        val (_, validation) = service()

        listOf("eur", "EURO", "EU", "").forEach {
            assertFailsWith<ValidationError>("expected '$it' to be rejected") {
                validation.validate(CreateAccountCommand(WORKSPACE, "cash", it, icon = null))
            }
        }
    }

    @Test
    fun `rejects an icon longer than the column`() {
        val (_, validation) = service()
        val tooLong = "x".repeat(ValidationConstants.MAX_ICON_LENGTH + 1)

        assertFailsWith<ValidationError> {
            validation.validate(CreateAccountCommand(WORKSPACE, "cash", "EUR", icon = tooLong))
        }
    }

    @Test
    fun `accepts a revision of an account that exists in the workspace`() {
        val (_, validation) = service(known = setOf(WORKSPACE to ACCOUNT))

        validation.validate(ReviseAccountCommand(WORKSPACE, ACCOUNT, "renamed", icon = null))
    }

    @Test
    fun `rejects a revision of an unknown account`() {
        val (_, validation) = service()

        assertFailsWith<NotFoundEntityException> {
            validation.validate(ReviseAccountCommand(WORKSPACE, ACCOUNT, "renamed", icon = null))
        }
    }

    @Test
    fun `looks the account up within the command's workspace`() {
        val (dao, validation) = service(known = setOf(WORKSPACE to ACCOUNT))

        assertFailsWith<NotFoundEntityException> {
            validation.validate(ReviseAccountCommand(OTHER_WORKSPACE, ACCOUNT, "renamed", icon = null))
        }

        // The same id in another workspace must not resolve — the lookup is scoped by both keys.
        assertEquals(listOf(OTHER_WORKSPACE to ACCOUNT), dao.asked)
    }

    @Test
    fun `accepts archiving and unarchiving an account that exists`() {
        val (_, validation) = service(known = setOf(WORKSPACE to ACCOUNT))

        // Setting a flag to the value it already holds is not a conflict, matching workspaces.
        validation.validate(SetAccountArchivedCommand(WORKSPACE, ACCOUNT, archived = true))
        validation.validate(SetAccountArchivedCommand(WORKSPACE, ACCOUNT, archived = false))
    }

    @Test
    fun `rejects archiving an unknown account`() {
        val (_, validation) = service()

        assertFailsWith<NotFoundEntityException> {
            validation.validate(SetAccountArchivedCommand(WORKSPACE, ACCOUNT, archived = true))
        }
    }

    private companion object {
        val WORKSPACE: UUID = UUID.fromString("0199a1c2-3d4e-7f80-8123-000000000001")
        val OTHER_WORKSPACE: UUID = UUID.fromString("0199a1c2-3d4e-7f80-8123-000000000002")
        val ACCOUNT: UUID = UUID.fromString("0199a1c2-3d4e-7f80-8123-4567890abcde")
    }
}
