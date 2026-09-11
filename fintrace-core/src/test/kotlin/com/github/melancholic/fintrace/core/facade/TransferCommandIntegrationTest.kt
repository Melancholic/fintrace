package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.dao.UsersDAO
import com.github.melancholic.fintrace.core.dao.WorkspaceDAO
import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.domain.command.CancelTransferCommand
import com.github.melancholic.fintrace.core.domain.command.CreateTransferCommand
import com.github.melancholic.fintrace.core.domain.command.ReviseTransferCommand
import com.github.melancholic.fintrace.core.exception.ActionConflictException
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.exception.ValidationError
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.context.support.WithMockUser
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The transfer aggregate through its real entry point (1.18).
 *
 * A transfer is the first command that writes **two** projection rows from **one** event (§4.5),
 * so most of what is checked here is the pair: that both rows are written, that they point at each
 * other, that a revision lands on the same two rows, and that a cancellation takes both away.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@WithMockUser(username = TestWorkspaces.TEST_SUBJECT)
class TransferCommandIntegrationTest(
    @Autowired private val facade: CommandFacade,
    @Autowired private val adminFacade: AdminFacade,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val workspaceDAO: WorkspaceDAO,
    @Autowired private val usersDAO: UsersDAO,
    @Autowired private val accountDAO: AccountProjectionDAO,
) {

    private lateinit var workspaceId: UUID
    private lateinit var euroAccount: UUID
    private lateinit var poundAccount: UUID

    @BeforeEach
    fun clean() {
        TestWorkspaces.reset(jdbc)
        workspaceId = TestWorkspaces.create(workspaceDAO, usersDAO)
        euroAccount = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "euro", currency = "EUR")
        poundAccount = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "pound", currency = "GBP")
    }

    // ------------------------------------------------------------------ create (1.18)

    @Test
    fun `writes one event and two rows`() {
        facade.processCommand(create())

        // One event over the transfer, two legs in t_operations. Two events — one per leg — would
        // leave a replay window in which the pair invariant is violated (§4.5).
        assertEquals(1, count("t_events"))
        assertEquals(2, count("t_operations"))
    }

    @Test
    fun `records the event over the transfer aggregate`() {
        val transfer = facade.processCommand(create())

        val event = events().single()
        assertEquals("TRANSFER", event.entityType)
        assertEquals("CREATED", event.eventType)
        assertEquals(transfer.id, event.entityId, "the aggregate is the transfer, not either leg")
    }

    @Test
    fun `stores the outgoing leg negative and the incoming positive`() {
        facade.processCommand(create(sourceAmount = BigDecimal("100.0000"), targetAmount = BigDecimal("85.0000")))

        // The sign comes from the leg's direction, not from a kind — both legs are TRANSFER. A
        // positive outgoing leg would credit both accounts and inflate every balance built on
        // SUM(amount) (§4.13).
        assertEquals(BigDecimal("-100.0000"), legOn(euroAccount).amount)
        assertEquals(BigDecimal("85.0000"), legOn(poundAccount).amount)
    }

    @Test
    fun `links the two legs to each other`() {
        val transfer = facade.processCommand(create())

        val source = legOn(euroAccount)
        val target = legOn(poundAccount)
        assertEquals(transfer.id, source.transferId)
        assertEquals(transfer.id, target.transferId)
        assertEquals(target.id, source.counterpartId, "each leg names the other")
        assertEquals(source.id, target.counterpartId)
    }

    @Test
    fun `files no category on either leg`() {
        facade.processCommand(create())

        // A transfer is neither income nor expense, so a leg has no category at all — and V0009
        // refuses to store one that has.
        assertNull(legOn(euroAccount).categoryId)
        assertNull(legOn(poundAccount).categoryId)
    }

    @Test
    fun `shares one business date, one record time and one comment across the legs`() {
        facade.processCommand(create(comment = "rent"))

        val source = legOn(euroAccount)
        val target = legOn(poundAccount)
        assertEquals(source.occurredAt, target.occurredAt, "one business date for the pair")
        assertEquals(source.recordedAt, target.recordedAt, "one clock read for the pair")
        assertEquals("rent", source.comment)
        assertEquals("rent", target.comment)
    }

    @Test
    fun `returns the pair it produced, each leg in its account's own currency`() {
        val transfer = facade.processCommand(
            create(sourceAmount = BigDecimal("100.0000"), targetAmount = BigDecimal("85.0000"))
        )

        assertEquals(euroAccount, transfer.source.accountId)
        assertEquals("EUR", transfer.source.currency)
        assertEquals(poundAccount, transfer.target.accountId)
        assertEquals("GBP", transfer.target.currency)
        assertTrue(transfer.source.amount.signum() < 0, "the returned outgoing leg is signed")
    }

    @Test
    fun `replays into the same two rows`() {
        facade.processCommand(create())
        val before = legs()

        jdbc.sql("DELETE FROM t_operations").update()
        adminFacade.replayWorkspace(workspaceId)

        // One event has to reproduce both rows, or a rebuild silently halves every transfer.
        assertEquals(before, legs())
    }

    // ------------------------------------------------------------------ revise (1.18)

    @Test
    fun `revises in place, keeping both leg ids`() {
        val created = facade.processCommand(create())

        facade.processCommand(revise(created.id, sourceAmount = BigDecimal("250.0000")))

        // The leg ids belong to the slot and are carried forward from the newest event: fresh ids
        // would leave the previous pair orphaned, and no rebuild would ever remove them.
        assertEquals(created.source.operationId, legOn(euroAccount).id)
        assertEquals(created.target.operationId, legOn(poundAccount).id)
        assertEquals(BigDecimal("-250.0000"), legOn(euroAccount).amount)
    }

    @Test
    fun `writes a second event and still exactly two rows`() {
        val created = facade.processCommand(create())

        facade.processCommand(revise(created.id))

        assertEquals(2, count("t_events"))
        assertEquals(2, count("t_operations"))
        assertEquals("REVISED", events().last().eventType)
        assertEquals(created.id, events().last().entityId)
    }

    @Test
    fun `keeps the legs pointing at each other after a revision`() {
        val created = facade.processCommand(create())

        facade.processCommand(revise(created.id))

        val source = legOn(euroAccount)
        val target = legOn(poundAccount)
        assertEquals(target.id, source.counterpartId)
        assertEquals(source.id, target.counterpartId)
        assertTrue(source.id != target.id, "the two legs stay distinct rows")
    }

    @Test
    fun `a revision can move the pair to other accounts`() {
        val created = facade.processCommand(create())
        val thirdAccount = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "third", currency = "EUR")

        facade.processCommand(revise(created.id, targetAccountId = thirdAccount))

        assertEquals(2, count("t_operations"))
        assertEquals(created.target.operationId, legOn(thirdAccount).id, "the incoming slot keeps its id")
        assertEquals(0, countOn(poundAccount), "the old account keeps no leg behind")
    }

    // ------------------------------------------------------------------ cancel (1.18)

    @Test
    fun `cancelling removes both rows and keeps the log`() {
        val created = facade.processCommand(create())

        facade.processCommand(CancelTransferCommand(workspaceId = workspaceId, transferId = created.id))

        // Both legs or neither: a surviving leg is the half-transfer the whole design refuses.
        assertEquals(0, count("t_operations"))
        assertEquals(2, count("t_events"))
    }

    @Test
    fun `classifies the cancellation over the transfer aggregate`() {
        val created = facade.processCommand(create())

        facade.processCommand(CancelTransferCommand(workspaceId = workspaceId, transferId = created.id))

        val cancellation = events().last()
        assertEquals("CANCELLED", cancellation.eventType)
        assertEquals("TRANSFER", cancellation.entityType)
        assertEquals(created.id, cancellation.entityId)
    }

    @Test
    fun `a cancelled transfer does not come back on replay`() {
        val created = facade.processCommand(create())
        facade.processCommand(CancelTransferCommand(workspaceId = workspaceId, transferId = created.id))

        adminFacade.replayWorkspace(workspaceId)

        // The cancel payload names both legs precisely so a rebuild knows which rows to drop.
        assertEquals(0, count("t_operations"))
    }

    // ------------------------------------------------------------------ validation (1.18)
    //
    // TransferValidationServiceImpl is still a no-op, so everything below fails until it is
    // written. Each case also asserts that nothing was appended: an event has already happened
    // and cannot be rejected afterwards, so a refused command must not reach the log.

    @Test
    fun `rejects a transfer from an account to itself`() {
        assertFailsWith<ValidationError> {
            facade.processCommand(create(targetAccountId = euroAccount))
        }

        assertEquals(0, count("t_events"))
    }

    @Test
    fun `rejects a transfer naming an account that does not exist`() {
        assertFailsWith<NotFoundEntityException> {
            facade.processCommand(create(targetAccountId = UUID.randomUUID()))
        }

        assertEquals(0, count("t_events"))
    }

    @Test
    fun `rejects a transfer naming an account in another workspace`() {
        val other = TestWorkspaces.create(workspaceDAO, usersDAO, name = "other-workspace")
        val foreign = TestWorkspaces.seedAccount(accountDAO, other, name = "foreign")

        assertFailsWith<NotFoundEntityException> {
            facade.processCommand(create(targetAccountId = foreign))
        }

        assertEquals(0, count("t_events"))
    }

    @Test
    fun `rejects a transfer touching an archived account`() {
        archive(poundAccount)

        // §4.8: archiving stands in for deleting an account, so it has to close the write path —
        // on either side of the pair.
        assertFailsWith<ActionConflictException> {
            facade.processCommand(create())
        }

        assertEquals(0, count("t_events"))
    }

    @Test
    fun `allows editing a transfer whose account was archived afterwards`() {
        val created = facade.processCommand(create())
        archive(poundAccount)

        // A closed account is still part of history: the legs it already carries stay editable,
        // or a typo on an archived account could never be corrected (§4.8).
        facade.processCommand(revise(created.id, targetAmount = BigDecimal("90.0000")))

        assertEquals(BigDecimal("90.0000"), legOn(poundAccount).amount)
    }

    @Test
    fun `refuses to move a leg onto an archived account`() {
        val created = facade.processCommand(create())
        val closed = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "closed", currency = "GBP")
        archive(closed)

        // Editing in place is one thing; moving money onto a closed account is new activity there.
        assertFailsWith<ActionConflictException> {
            facade.processCommand(revise(created.id, targetAccountId = closed))
        }

        assertEquals(1, count("t_events"), "a rejected command writes nothing")
        assertEquals(0, countOn(closed))
    }

    @Test
    fun `rejects a non-positive amount on either leg`() {
        assertFailsWith<ValidationError> { facade.processCommand(create(sourceAmount = BigDecimal.ZERO)) }
        assertFailsWith<ValidationError> { facade.processCommand(create(targetAmount = BigDecimal("-1.0000"))) }

        assertEquals(0, count("t_events"))
    }

    @Test
    fun `rejects a transfer dated in the future`() {
        assertFailsWith<ValidationError> {
            facade.processCommand(create(occurredAt = LocalDateTime.now().plusDays(1)))
        }

        assertEquals(0, count("t_events"))
    }

    @Test
    fun `rejects a revision of an unknown transfer`() {
        assertFailsWith<NotFoundEntityException> { facade.processCommand(revise(UUID.randomUUID())) }

        assertEquals(0, count("t_events"))
    }

    @Test
    fun `rejects a revision that breaks a rule the create path enforces`() {
        val created = facade.processCommand(create())

        assertFailsWith<ValidationError> {
            facade.processCommand(revise(created.id, targetAccountId = euroAccount))
        }

        // A rule written once for OperationStateCommand cannot be applied to create and forgotten
        // on revise; the same has to hold for the pair.
        assertEquals(1, count("t_events"))
    }

    @Test
    fun `rejects cancelling an unknown transfer`() {
        assertFailsWith<NotFoundEntityException> {
            facade.processCommand(CancelTransferCommand(workspaceId = workspaceId, transferId = UUID.randomUUID()))
        }

        assertEquals(0, count("t_events"))
    }

    @Test
    fun `rejects cancelling the same transfer twice`() {
        val created = facade.processCommand(create())
        facade.processCommand(CancelTransferCommand(workspaceId = workspaceId, transferId = created.id))

        assertFailsWith<NotFoundEntityException> {
            facade.processCommand(CancelTransferCommand(workspaceId = workspaceId, transferId = created.id))
        }

        assertEquals(2, count("t_events"), "the second attempt writes nothing")
    }

    private fun create(
        occurredAt: LocalDateTime = OCCURRED_AT,
        sourceAccountId: UUID = this.euroAccount,
        sourceAmount: BigDecimal = BigDecimal("100.0000"),
        targetAccountId: UUID = this.poundAccount,
        targetAmount: BigDecimal = BigDecimal("85.0000"),
        comment: String? = null,
    ) = CreateTransferCommand(
        workspaceId = workspaceId,
        occurredAt = occurredAt,
        sourceAccountId = sourceAccountId,
        sourceAmount = sourceAmount,
        targetAccountId = targetAccountId,
        targetAmount = targetAmount,
        comment = comment,
    )

    private fun revise(
        transferId: UUID,
        occurredAt: LocalDateTime = OCCURRED_AT,
        sourceAccountId: UUID = this.euroAccount,
        sourceAmount: BigDecimal = BigDecimal("120.0000"),
        targetAccountId: UUID = this.poundAccount,
        targetAmount: BigDecimal = BigDecimal("100.0000"),
        comment: String? = null,
    ) = ReviseTransferCommand(
        workspaceId = workspaceId,
        transferId = transferId,
        occurredAt = occurredAt,
        sourceAccountId = sourceAccountId,
        sourceAmount = sourceAmount,
        targetAccountId = targetAccountId,
        targetAmount = targetAmount,
        comment = comment,
    )

    private fun archive(accountId: UUID) = jdbc
        .sql("UPDATE t_accounts SET archived = true WHERE id = :id")
        .param("id", accountId)
        .update()

    private fun count(table: String) =
        jdbc.sql("SELECT count(*) FROM $table").query(Int::class.java).single()

    private fun countOn(accountId: UUID) = jdbc
        .sql("SELECT count(*) FROM t_operations WHERE account_id = :id")
        .param("id", accountId)
        .query(Int::class.java).single()

    private fun legOn(accountId: UUID): LegRow = legs().single { it.accountId == accountId }

    private fun legs(): List<LegRow> = jdbc
        .sql(
            """
            SELECT id, account_id, amount, category_id, transfer_id, counterpart_id, comment,
                   occurred_at, recorded_at
            FROM t_operations ORDER BY amount
            """
        )
        .query { rs, _ ->
            LegRow(
                id = rs.getObject("id", UUID::class.java),
                accountId = rs.getObject("account_id", UUID::class.java),
                amount = rs.getBigDecimal("amount"),
                categoryId = rs.getObject("category_id", UUID::class.java),
                transferId = rs.getObject("transfer_id", UUID::class.java),
                counterpartId = rs.getObject("counterpart_id", UUID::class.java),
                comment = rs.getString("comment"),
                occurredAt = rs.getObject("occurred_at", LocalDateTime::class.java),
                recordedAt = rs.getObject("recorded_at", LocalDateTime::class.java),
            )
        }
        .list()

    private fun events(): List<EventRow> = jdbc
        .sql("SELECT aggregate_type, aggregate_id, event_type FROM t_events ORDER BY id")
        .query { rs, _ ->
            EventRow(
                entityType = rs.getString("aggregate_type"),
                entityId = rs.getObject("aggregate_id", UUID::class.java),
                eventType = rs.getString("event_type"),
            )
        }
        .list()

    private data class LegRow(
        val id: UUID,
        val accountId: UUID,
        val amount: BigDecimal,
        val categoryId: UUID?,
        val transferId: UUID?,
        val counterpartId: UUID?,
        val comment: String?,
        val occurredAt: LocalDateTime,
        val recordedAt: LocalDateTime,
    )

    private data class EventRow(
        val entityType: String,
        val entityId: UUID,
        val eventType: String,
    )

    private companion object {
        val OCCURRED_AT: LocalDateTime = LocalDateTime.parse("2026-03-15T14:30:00")
    }
}
