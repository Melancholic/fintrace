package com.github.melancholic.fintrace.core

import com.github.melancholic.fintrace.core.TestWorkspaces.createWithCategories
import com.github.melancholic.fintrace.core.api.v1.dto.CreateWorkspaceRequest
import com.github.melancholic.fintrace.core.dao.UsersDAO
import com.github.melancholic.fintrace.core.dao.WorkspaceDAO
import com.github.melancholic.fintrace.core.service.WorkspaceService
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
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

	fun ownerId(usersDAO: UsersDAO): UUID = usersDAO.getUserIdByExternalId(TEST_SUBJECT)
		.orElseThrow { IllegalStateException("The stub user seeded by V0004 is missing") }
}
