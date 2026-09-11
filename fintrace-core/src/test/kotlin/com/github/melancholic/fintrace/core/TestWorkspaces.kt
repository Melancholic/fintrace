package com.github.melancholic.fintrace.core

import com.github.melancholic.fintrace.core.TestWorkspaces.create
import com.github.melancholic.fintrace.core.TestWorkspaces.createWithCategories
import com.github.melancholic.fintrace.core.TestWorkspaces.seedAccount
import com.github.melancholic.fintrace.core.api.v1.dto.CreateWorkspaceRequest
import com.github.melancholic.fintrace.core.dao.UsersDAO
import com.github.melancholic.fintrace.core.dao.WorkspaceDAO
import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.CategoryProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import com.github.melancholic.fintrace.core.domain.projection.AccountProjection
import com.github.melancholic.fintrace.core.domain.projection.CategoryProjection
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.service.WorkspaceService
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

/**
 * Every table carrying `workspace_id` has a real foreign key to `t_workspaces`, so a test can no
 * longer invent a workspace id: the row has to exist before an event or a projection row can
 * reference it.
 *
 * Workspaces are created **through `WorkspaceDAO`** rather than by an `INSERT` written here. A
 * hand-written fixture drifts from the schema the moment a column is added — which is exactly how
 * `updated_at` and `version` broke every integration test — while a DAO call cannot.
 *
 * Assertions deliberately keep reading raw rows. If a test writes and reads through the same
 * mapper, a mapping bug makes the two agree and the row on disk is never checked.
 */
internal object TestWorkspaces {

	/**
	 * What a test authenticates as. It is the seeded user's `external_id`, not its username,
	 * because `IdentityProvider` resolves the caller by subject — `Authentication.getName()`,
	 * which is the `sub` claim once M5 puts a real token behind it.
	 */
	const val TEST_SUBJECT = "stub:testuser"

	/**
	 * Wipes every workspace, and with it — by `ON DELETE CASCADE` — every event and projection
	 * row. Still raw SQL: deletion is soft in the domain, so no DAO can do this. It becomes a call
	 * to the retention job (task 1.5b) once that exists.
	 */
	fun reset(jdbc: JdbcClient) {
		jdbc.sql("DELETE FROM t_workspaces").update()
	}

    /**
     * A **bare** workspace: the row and nothing else, so `t_events` starts empty and a test can
     * assert absolute event counts. It has **no categories**, because seeding them is part of
     * `WorkspaceService.createWorkspace` rather than of the insert.
     *
     * Use [createWithCategories] for anything that needs the system categories — from 1.16 onward
     * that is any test touching an operation, since operations carry a `category_id`.
     */
	fun create(
		workspaceDAO: WorkspaceDAO,
		usersDAO: UsersDAO,
		name: String = "test-workspace",
		currency: String = "EUR",
	): UUID = workspaceDAO.create(ownerId(usersDAO), CreateWorkspaceRequest(name, currency))

    /**
     * A workspace as the application makes one: created through the service, so the four system
     * categories are seeded (§4.7).
     *
     * Costs four category events, so a test using this counts events relative to a baseline rather
     * than from zero. Goes through the service rather than the facade so it needs no security
     * context — the caller is passed explicitly — and through a `TransactionTemplate` because the
     * service is `@Transactional(MANDATORY)`.
     */
    fun createWithCategories(
        transactions: TransactionTemplate,
        workspaceService: WorkspaceService,
        usersDAO: UsersDAO,
        name: String = "test-workspace",
        currency: String = "EUR",
    ): UUID = transactions.execute {
        workspaceService.createWorkspace(ownerId(usersDAO), CreateWorkspaceRequest(name, currency)).id
    }

    /**
     * An account and a category written **straight to their projections**, with no event behind
     * them.
     *
     * From 1.16 an operation command is rejected unless its account and category exist, so a test
     * about operations needs both to be there. Creating them through the command path would cost
     * two events, which breaks every test asserting an absolute count in `t_events` — the reason
     * [create] exists at all. Going through the DAO keeps the row shape honest without touching
     * the log.
     *
     * A test that replays must not expect these rows to survive: nothing in the log describes
     * them.
     */
    fun seedAccount(
        accountDAO: AccountProjectionDAO,
        workspaceId: UUID,
        name: String = "seeded-account",
        currency: String = "EUR",
    ): UUID = accountDAO.createOrUpdate(
        AccountProjection(
            id = UUID.randomUUID(),
            workspaceId = workspaceId,
            name = name,
            currency = currency,
            archived = false,
            icon = null,
            recordedAt = LocalDateTime.now(),
        )
    )

    fun seedCategory(
        categoryDAO: CategoryProjectionDAO,
        workspaceId: UUID,
        kind: CategoryKind = CategoryKind.EXPENSE,
        name: String = "seeded-category",
    ): UUID = categoryDAO.createOrUpdate(
        CategoryProjection(
            id = UUID.randomUUID(),
            workspaceId = workspaceId,
            parentId = null,
            name = name,
            kind = kind,
            archived = false,
            systemCode = null,
            icon = null,
            recordedAt = LocalDateTime.now(),
        )
    )

    /**
     * A transfer's two legs, written straight to the projection: one `transfer_id`, each leg's
     * `counterpart_id` pointing at the other, the outgoing amount negative (§4.5).
     *
     * The transfer command (1.18) does not exist yet, so this is the only way to produce a row
     * carrying a `transfer_id` — which is what 1.19's guard keys off. Like [seedAccount] there is
     * no event behind these rows, so a replay does not reproduce them.
     *
     * A leg carries no category, which V0009 now enforces at the storage layer.
     */
    fun seedTransferPair(
        operationDAO: OperationProjectionDAO,
        workspaceId: UUID,
        sourceAccountId: UUID,
        targetAccountId: UUID,
        transferId: UUID = UUID.randomUUID(),
        sourceAmount: BigDecimal = BigDecimal("100.0000"),
        targetAmount: BigDecimal = sourceAmount,
        comment: String? = null,
        occurredAt: LocalDateTime = LocalDateTime.now(),
    ): Pair<UUID, UUID> {
        val sourceId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        // One clock read for the pair: a command writes both legs from a single timestamp, and the
        // read side rejects a pair whose legs disagree about recordedAt.
        val recordedAt = LocalDateTime.now()

        fun leg(id: UUID, counterpartId: UUID, accountId: UUID, signed: BigDecimal) = OperationProjection(
            id = id,
            workspaceId = workspaceId,
            amount = signed,
            kind = OperationKind.TRANSFER,
            accountId = accountId,
            categoryId = null,
            transferId = transferId,
            counterpartId = counterpartId,
            comment = comment,
            externalRef = null,
            occurredAt = occurredAt,
            recordedAt = recordedAt,
        )

        operationDAO.createOrUpdate(leg(sourceId, targetId, sourceAccountId, sourceAmount.negate()))
        operationDAO.createOrUpdate(leg(targetId, sourceId, targetAccountId, targetAmount))
        return sourceId to targetId
    }

	fun ownerId(usersDAO: UsersDAO): UUID = usersDAO.getUserIdByExternalId(TEST_SUBJECT)
		.orElseThrow { IllegalStateException("The stub user seeded by V0004 is missing") }
}
