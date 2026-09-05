package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.security.IdentityProvider
import com.github.melancholic.fintrace.core.service.WorkspaceService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

interface ProjectionFacade {
    fun getOperationProjection(workspaceId: UUID, operationId: UUID): OperationProjection
}

@Service
class ProjectionFacadeImpl(
    private val projectionDAO: OperationProjectionDAO,
    private val workspaceService: WorkspaceService,
    private val identityProvider: IdentityProvider
) : ProjectionFacade {

    @Transactional
    override fun getOperationProjection(
        workspaceId: UUID,
        operationId: UUID
    ): OperationProjection {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        return projectionDAO.getById(workspace.id, operationId)
    }

}