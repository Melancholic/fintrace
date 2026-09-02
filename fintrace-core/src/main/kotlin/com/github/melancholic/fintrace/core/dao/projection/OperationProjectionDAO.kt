package com.github.melancholic.fintrace.core.dao.projection

import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.*


interface OperationProjectionDAO {
    fun create(projection: OperationProjection): UUID
    fun getById(workspaceId: UUID, operationId: UUID): OperationProjection
    fun removeAll(workspaceId: UUID)
}

@Repository
class OperationProjectionDAOImpl(
    private val jdbc: JdbcClient
) : OperationProjectionDAO {

    override fun create(projection: OperationProjection): UUID {
        return jdbc.sql(INSERT)
            .param("id", projection.id)
            .param("workspaceId", projection.workspaceId)
            .param("amount", projection.amount)
            .param("occurredAt", projection.occurredAt)
            .param("recordedAt", projection.recordedAt)
            .query(UUID::class.java)
            .single()
    }

    override fun getById(
        workspaceId: UUID,
        operationId: UUID
    ): OperationProjection {
        return jdbc.sql(SELECT)
            .param("id", operationId)
            .param("workspaceId", workspaceId)
            .query(OperationProjection::class.java)
            .optional()
            .orElseThrow { NotFoundEntityException("Operation not found into workspace (workspaceId='$workspaceId', operationId='$operationId')") }
    }

    override fun removeAll(workspaceId: UUID) {
        jdbc.sql(DELETE_BY_WORKSPACE)
            .param("workspaceId", workspaceId)
            .update()
    }

    private companion object {
        const val INSERT = """
            INSERT INTO t_operations (id, workspace_id, amount, occurred_at, recorded_at)
            VALUES (:id, :workspaceId, :amount, :occurredAt, :recordedAt)
            RETURNING id
        """

        const val SELECT = """
            SELECT id, workspace_id, amount, occurred_at, recorded_at from t_operations
            WHERE workspace_id = :workspaceId AND id = :id
        """

        const val DELETE_BY_WORKSPACE = """
            DELETE from t_operations WHERE workspace_id = :workspaceId
        """
    }
}