package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.ProjectionDAORegistry
import com.github.melancholic.fintrace.core.domain.projection.AccountProjection
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.security.IdentityProvider
import com.github.melancholic.fintrace.core.service.WorkspaceService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

interface ProjectionFacade {
    fun getOperation(workspaceId: UUID, operationId: UUID): OperationProjection
    fun getAccount(workspaceId: UUID, accountId: UUID): AccountProjection
    fun getAllAccounts(workspaceId: UUID, includeArchived: Boolean): List<AccountProjection>
}

@Service
class ProjectionFacadeImpl(
    private val daoRegistry: ProjectionDAORegistry,
    private val workspaceService: WorkspaceService,
    private val identityProvider: IdentityProvider
) : ProjectionFacade {

    @Transactional(readOnly = true)
    override fun getOperation(
        workspaceId: UUID,
        operationId: UUID
    ): OperationProjection {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        return daoRegistry[OperationProjectionDAO::class.java].getById(workspace.id, operationId)
    }

    @Transactional(readOnly = true)
    override fun getAccount(
        workspaceId: UUID,
        accountId: UUID
    ): AccountProjection {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        return daoRegistry[AccountProjectionDAO::class.java].getById(workspace.id, accountId)
    }

    @Transactional(readOnly = true)
    override fun getAllAccounts(
        workspaceId: UUID,
        includeArchived: Boolean
    ): List<AccountProjection> {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        return daoRegistry[AccountProjectionDAO::class.java].getAllAccounts(workspace.id, includeArchived)
    }

}