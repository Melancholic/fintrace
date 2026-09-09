package com.github.melancholic.fintrace.core.dao.projection

import com.github.melancholic.fintrace.core.domain.projection.AccountProjection
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.service.projection.ProjectionTarget
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.*


interface AccountProjectionDAO : ProjectionDAO<AccountProjection> {
    override fun projectionTarget() = ProjectionTarget.ACCOUNT
    override fun supportedClass() = AccountProjection::class.java

    override fun createOrUpdate(projection: AccountProjection): UUID
    override fun getById(workspaceId: UUID, id: UUID): AccountProjection
    fun remove(workspaceId: UUID, accountId: UUID)
    fun exists(workspaceId: UUID, accountId: UUID): Boolean
    fun getAllAccounts(workspaceId: UUID, includeArchived: Boolean): List<AccountProjection>
}


@Repository
class AccountProjectionDAOImpl(
    private val jdbc: JdbcClient
) : AccountProjectionDAO {
    override fun createOrUpdate(projection: AccountProjection): UUID {
        return jdbc.sql(INSERT_OR_UPDATE)
            .param("id", projection.id)
            .param("workspaceId", projection.workspaceId)
            .param("name", projection.name)
            .param("currency", projection.currency)
            .param("icon", projection.icon)
            .param("archived", projection.archived)
            .param("recordedAt", projection.recordedAt)
            .query(UUID::class.java)
            .single()
    }

    override fun getById(workspaceId: UUID, id: UUID): AccountProjection {
        return jdbc.sql(SELECT_BY_ID).param("id", id).param("workspaceId", workspaceId)
            .query(AccountProjection::class.java).optional()
            .orElseThrow { NotFoundEntityException("Account not found into workspace (workspaceId='$workspaceId', accountId='$id')") }
    }

    override fun remove(workspaceId: UUID, accountId: UUID) {
        remove(workspaceId, setOf(accountId))
    }

    override fun exists(workspaceId: UUID, accountId: UUID): Boolean {
        return jdbc.sql(CHECK_EXISTS).param("id", accountId).param("workspaceId", workspaceId)
            .query(Boolean::class.java).single()
    }

    override fun getAllAccounts(
        workspaceId: UUID,
        includeArchived: Boolean
    ): List<AccountProjection> {

        var sql = SELECT_ALL
        if (!includeArchived) {
            sql += " AND not archived"
        }

        return jdbc.sql(sql)
            .param("workspaceId", workspaceId)
            .query(AccountProjection::class.java)
            .list()
            .filterNotNull()
    }

    override fun removeAll(workspaceId: UUID) {
        jdbc.sql(DELETE_BY_WORKSPACE).param("workspaceId", workspaceId).update()
    }

    override fun remove(workspaceId: UUID, ids: Set<UUID>) {
        jdbc.sql(DELETE_BY_IDS_AND_WORKSPACE).param("workspaceId", workspaceId).param("ids", ids).update()
    }

    companion object {
        const val TABLE_NAME = "t_accounts"

        const val INSERT_OR_UPDATE = """
            INSERT INTO $TABLE_NAME (id, workspace_id, name, currency, archived, recorded_at, icon)
            VALUES (:id, :workspaceId, :name, :currency, :archived, :recordedAt, :icon)
            ON CONFLICT (id) DO UPDATE SET
                name        = EXCLUDED.name,
                currency    = EXCLUDED.currency,
                icon        = EXCLUDED.icon,
                archived    = EXCLUDED.archived,
                recorded_at = EXCLUDED.recorded_at
            WHERE $TABLE_NAME.workspace_id = EXCLUDED.workspace_id
            RETURNING id
        """

        const val SELECT_ALL = """
            SELECT *
            from $TABLE_NAME
            WHERE workspace_id = :workspaceId
        """

        const val SELECT_BY_ID = SELECT_ALL + """
            AND id = :id
        """

        const val CHECK_EXISTS = """
            SELECT EXISTS(SELECT 1 FROM $TABLE_NAME WHERE workspace_id = :workspaceId AND id = :id)
        """

        const val DELETE_BY_WORKSPACE = """
            DELETE from $TABLE_NAME WHERE workspace_id = :workspaceId
        """

        const val DELETE_BY_IDS_AND_WORKSPACE = """
            DELETE from $TABLE_NAME WHERE workspace_id = :workspaceId AND id IN (:ids)
        """
    }
}