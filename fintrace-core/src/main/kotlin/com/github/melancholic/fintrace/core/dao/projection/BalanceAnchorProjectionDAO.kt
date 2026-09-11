package com.github.melancholic.fintrace.core.dao.projection

import com.github.melancholic.fintrace.core.domain.projection.BalanceAnchorProjection
import com.github.melancholic.fintrace.core.exception.ApplicationException
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.service.projection.ProjectionTarget
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.*


interface BalanceAnchorProjectionDAO : ProjectionDAO<BalanceAnchorProjection> {
    override fun projectionTarget() = ProjectionTarget.BALANCE_ANCHOR
    override fun supportedClass() = BalanceAnchorProjection::class.java

    override fun createOrUpdate(projection: BalanceAnchorProjection): UUID
    override fun getById(workspaceId: UUID, id: UUID): BalanceAnchorProjection
    fun getAll(workspaceId: UUID, accountId: UUID): List<BalanceAnchorProjection>
    fun getById(workspaceId: UUID, accountId: UUID, anchorId: UUID): BalanceAnchorProjection
    fun isLast(requested: BalanceAnchorProjection): Boolean
}

@Repository
class BalanceAnchorProjectionDAOImpl(
    private val jdbc: JdbcClient
) : BalanceAnchorProjectionDAO {
    override fun createOrUpdate(projection: BalanceAnchorProjection): UUID {
        return jdbc.sql(INSERT)
            .param("id", projection.id)
            .param("workspaceId", projection.workspaceId)
            .param("accountId", projection.accountId)
            .param("value", projection.value)
            .param("recordedAt", projection.recordedAt)
            .query(UUID::class.java)
            .single()
    }

    override fun getById(workspaceId: UUID, id: UUID): BalanceAnchorProjection {
        return jdbc.sql(SELECT_BY_ID)
            .param("id", id)
            .param("workspaceId", workspaceId)
            .query(BalanceAnchorProjection::class.java).optional()
            .orElseThrow { NotFoundEntityException("Balance Anchor not found in workspace (workspaceId='$workspaceId', anchorId='$id')") }
    }

    override fun getAll(
        workspaceId: UUID,
        accountId: UUID
    ): List<BalanceAnchorProjection> = jdbc.sql(SELECT_ALL_BY_ACCOUNT_ID + " ORDER BY occurred_at desc")
        .param("workspaceId", workspaceId)
        .param("accountId", accountId)
        .query(BalanceAnchorProjection::class.java)
        .list() as List<BalanceAnchorProjection>

    override fun getById(
        workspaceId: UUID,
        accountId: UUID,
        anchorId: UUID
    ): BalanceAnchorProjection {
        return jdbc.sql(SELECT_BY_WORKSPACE_ACCOUNT_ANCHOR)
            .param("workspaceId", workspaceId)
            .param("accountId", accountId)
            .param("anchorId", anchorId)
            .query(BalanceAnchorProjection::class.java).optional()
            .orElseThrow { NotFoundEntityException("Balance Anchor not found for account (workspaceId='$workspaceId', accountId='$accountId', anchorId='$anchorId')") }
    }

    override fun isLast(requested: BalanceAnchorProjection): Boolean = jdbc.sql(IS_LATEST)
        .param("workspaceId", requested.workspaceId)
        .param("accountId", requested.accountId)
        .param("anchorId", requested.id)
        .query(Boolean::class.java)
        .optional()
        .orElseThrow { ApplicationException("Couldn't check that the balance anchor is the latest: nothing was returned") }

    override fun removeAll(workspaceId: UUID) {
        jdbc.sql(DELETE_BY_WORKSPACE)
            .param("workspaceId", workspaceId)
            .update()
    }

    override fun remove(workspaceId: UUID, ids: Set<UUID>) {
        jdbc.sql(DELETE_BY_IDS_AND_WORKSPACE)
            .param("workspaceId", workspaceId)
            .param("ids", ids)
            .update()
    }

    companion object {
        const val TABLE_NAME = "t_balance_anchors"

        const val INSERT = """
            INSERT INTO $TABLE_NAME (id, workspace_id, account_id, value, occurred_at, recorded_at)
            VALUES (:id, :workspaceId, :accountId, :value, :recordedAt, :recordedAt)
            RETURNING id
        """

        const val SELECT_ALL = """
            SELECT id, workspace_id, account_id, value, occurred_at, recorded_at
            from $TABLE_NAME
            WHERE workspace_id = :workspaceId
        """

        const val SELECT_ALL_BY_ACCOUNT_ID = SELECT_ALL + " AND account_id = :accountId"

        const val SELECT_BY_WORKSPACE_ACCOUNT_ANCHOR = SELECT_ALL_BY_ACCOUNT_ID + """
            AND id = :anchorId
        """

        const val IS_LATEST = """
            SELECT :anchorId = (
                SELECT id FROM $TABLE_NAME
                WHERE workspace_id = :workspaceId AND account_id = :accountId
                ORDER BY occurred_at DESC
                LIMIT 1
            )
        """

        const val SELECT_BY_ID = SELECT_ALL + """
            AND id = :id
        """

        const val DELETE_BY_WORKSPACE = """
            DELETE from $TABLE_NAME WHERE workspace_id = :workspaceId
        """

        const val DELETE_BY_IDS_AND_WORKSPACE = DELETE_BY_WORKSPACE + " AND id IN (:ids)"
    }
}