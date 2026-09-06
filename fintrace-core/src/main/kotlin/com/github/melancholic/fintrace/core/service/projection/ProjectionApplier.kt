package com.github.melancholic.fintrace.core.service.projection

import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.domain.projection.Projection
import org.springframework.stereotype.Service
import java.util.*

interface ProjectionApplier {
    fun apply(change: ProjectionChange)
    fun clear(workspaceId: UUID)
}

@Service
class ProjectionApplierImpl(
    private val operationsDAO: OperationProjectionDAO
) : ProjectionApplier {
    override fun apply(change: ProjectionChange) {
        return when (change) {
            is ProjectionChange.Upsert -> change.rows.forEach { upsert(it) }
            is ProjectionChange.Remove -> remove(change)
        }
    }

    override fun clear(workspaceId: UUID) {
        operationsDAO.removeAll(workspaceId)
    }

    private fun upsert(row: Projection) = when (row) {
        is OperationProjection -> operationsDAO.createOrUpdate(row)
    }

    private fun remove(change: ProjectionChange.Remove) = when (change.target) {
        ProjectionTarget.OPERATION -> operationsDAO.remove(change.workspaceId, change.ids)
        ProjectionTarget.ACCOUNT -> TODO()
        ProjectionTarget.CATEGORY -> TODO()
        ProjectionTarget.ANCHOR -> TODO()
    }
}