package com.github.melancholic.fintrace.core.dao.projection

import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.service.projection.ProjectionTarget
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.*


interface OperationProjectionDAO : ProjectionDAO<OperationProjection> {
    override fun projectionTarget() = ProjectionTarget.OPERATION
    override fun supportedClass() = OperationProjection::class.java

    override fun createOrUpdate(projection: OperationProjection): UUID
    override fun getById(workspaceId: UUID, operationId: UUID): OperationProjection
    fun remove(workspaceId: UUID, id: UUID)
    fun exists(workspaceId: UUID, operationId: UUID): Boolean
}

@Repository
class OperationProjectionDAOImpl(
    private val jdbc: JdbcClient
) : OperationProjectionDAO {

    override fun createOrUpdate(projection: OperationProjection): UUID {
        return jdbc.sql(INSERT_OR_UPDATE).param("id", projection.id).param("workspaceId", projection.workspaceId)
            .param("amount", projection.amount)
            .param("kind", projection.kind.name)
            .param("accountId", projection.accountId)
            .param("categoryId", projection.categoryId)
            .param("transferId", projection.transferId)
            .param("counterpartId", projection.counterpartId)
            .param("comment", projection.comment)
            .param("externalRef", projection.externalRef)
            .param("occurredAt", projection.occurredAt)
            .param("recordedAt", projection.recordedAt)
            .query(
                UUID::class.java
            ).single()
    }

    override fun getById(
        workspaceId: UUID, operationId: UUID
    ): OperationProjection {
        return jdbc.sql(SELECT)
            .param("id", operationId)
            .param("workspaceId", workspaceId)
            .query(OperationProjection::class.java).optional()
            .orElseThrow { NotFoundEntityException("Operation not found into workspace (workspaceId='$workspaceId', operationId='$operationId')") }
    }

    override fun removeAll(workspaceId: UUID) {
        jdbc.sql(DELETE_BY_WORKSPACE).param("workspaceId", workspaceId).update()
    }

    override fun remove(workspaceId: UUID, id: UUID) {
        remove(workspaceId, setOf(id))
    }

    override fun remove(workspaceId: UUID, ids: Set<UUID>) {
        jdbc.sql(DELETE_BY_IDS_AND_WORKSPACE).param("workspaceId", workspaceId).param("ids", ids).update()
    }

    override fun exists(workspaceId: UUID, operationId: UUID): Boolean {
        return jdbc.sql(CHECK_EXISTS).param("id", operationId).param("workspaceId", workspaceId)
            .query(Boolean::class.java)
            .single()
    }

    private companion object {
        const val TABLE_NAME = "t_operations"

        const val INSERT_OR_UPDATE = """
            INSERT INTO $TABLE_NAME (id, workspace_id, amount, kind, account_id, category_id,
                                     transfer_id, counterpart_id, comment, external_ref,
                                     occurred_at, recorded_at)
            VALUES (:id, :workspaceId, :amount, :kind, :accountId, :categoryId,
                    :transferId, :counterpartId, :comment, :externalRef,
                    :occurredAt, :recordedAt)
            ON CONFLICT (id) DO UPDATE SET
                amount         = EXCLUDED.amount,
                kind           = EXCLUDED.kind,
                account_id     = EXCLUDED.account_id,
                category_id    = EXCLUDED.category_id,
                transfer_id    = EXCLUDED.transfer_id,
                counterpart_id = EXCLUDED.counterpart_id,
                comment        = EXCLUDED.comment,
                external_ref   = EXCLUDED.external_ref,
                occurred_at    = EXCLUDED.occurred_at,
                recorded_at    = EXCLUDED.recorded_at
            WHERE $TABLE_NAME.workspace_id = EXCLUDED.workspace_id
            RETURNING id
        """

        const val SELECT = """
            SELECT id, workspace_id, amount, kind, account_id, category_id, transfer_id,
                   counterpart_id, comment, external_ref, occurred_at, recorded_at
            from $TABLE_NAME
            WHERE workspace_id = :workspaceId AND id = :id
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