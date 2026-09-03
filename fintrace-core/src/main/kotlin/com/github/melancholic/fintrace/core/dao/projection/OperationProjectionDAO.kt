package com.github.melancholic.fintrace.core.dao.projection

import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.*


interface OperationProjectionDAO {
    fun createOrUpdate(projection: OperationProjection): UUID
    fun getById(workspaceId: UUID, operationId: UUID): OperationProjection
    fun removeAll(workspaceId: UUID)
    fun remove(workspaceId: UUID, id: UUID)
    fun exists(workspaceId: UUID, operationId: UUID): Boolean
}

@Repository
class OperationProjectionDAOImpl(
    private val jdbc: JdbcClient
) : OperationProjectionDAO {

    override fun createOrUpdate(projection: OperationProjection): UUID {
        return jdbc.sql(INSERT_OR_UPDATE).param("id", projection.id).param("workspaceId", projection.workspaceId)
            .param("amount", projection.amount).param("occurredAt", projection.occurredAt)
            .param("recordedAt", projection.recordedAt).query(UUID::class.java).single()
    }

    override fun getById(
        workspaceId: UUID, operationId: UUID
    ): OperationProjection {
        return jdbc.sql(SELECT).param("id", operationId).param("workspaceId", workspaceId)
            .query(OperationProjection::class.java).optional()
            .orElseThrow { NotFoundEntityException("Operation not found into workspace (workspaceId='$workspaceId', operationId='$operationId')") }
    }

    override fun removeAll(workspaceId: UUID) {
        jdbc.sql(DELETE_BY_WORKSPACE).param("workspaceId", workspaceId).update()
    }

    override fun remove(workspaceId: UUID, id: UUID) {
        jdbc.sql(DELETE_BY_ID_AND_WORKSPACE).param("workspaceId", workspaceId).param("id", id).update()
    }

    override fun exists(workspaceId: UUID, operationId: UUID): Boolean {
        return jdbc.sql(CHECK_EXISTS).param("id", operationId).param("workspaceId", workspaceId)
            .query(Boolean::class.java)
            .single()
    }

    private companion object {
        const val INSERT_OR_UPDATE = """
            INSERT INTO t_operations (id, workspace_id, amount, occurred_at, recorded_at)
            VALUES (:id, :workspaceId, :amount, :occurredAt, :recordedAt)
            ON CONFLICT (id) DO UPDATE SET
                amount      = EXCLUDED.amount,
                occurred_at = EXCLUDED.occurred_at,
                recorded_at = EXCLUDED.recorded_at
            WHERE t_operations.workspace_id = EXCLUDED.workspace_id
            RETURNING id
        """

        const val SELECT = """
            SELECT id, workspace_id, amount, occurred_at, recorded_at from t_operations
            WHERE workspace_id = :workspaceId AND id = :id
        """

        const val CHECK_EXISTS = """
            SELECT EXISTS(SELECT 1 FROM t_operations WHERE workspace_id = :workspaceId AND id = :id)
        """

        const val DELETE_BY_WORKSPACE = """
            DELETE from t_operations WHERE workspace_id = :workspaceId
        """

        const val DELETE_BY_ID_AND_WORKSPACE = """
            DELETE from t_operations WHERE workspace_id = :workspaceId AND id = :id
        """
    }
}