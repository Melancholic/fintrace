package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

interface ProjectionFacade {
    fun getOperationProjection(workspaceId: UUID, operationId: UUID): OperationProjection
}

@Service
class ProjectionFacadeImpl(
    private val projectionDAO: OperationProjectionDAO
) : ProjectionFacade {

    @Transactional
    override fun getOperationProjection(
        workspaceId: UUID,
        operationId: UUID
    ): OperationProjection {
        return projectionDAO.getById(workspaceId, operationId)
    }

}