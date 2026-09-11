package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.BalanceAnchorProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.CategoryProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.entity.Transfer
import com.github.melancholic.fintrace.core.domain.projection.AccountProjection
import com.github.melancholic.fintrace.core.domain.projection.BalanceAnchorProjection
import com.github.melancholic.fintrace.core.domain.projection.CategoryProjection
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.security.IdentityProvider
import com.github.melancholic.fintrace.core.service.WorkspaceService
import com.github.melancholic.fintrace.core.service.transfer.TransferLoader
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

interface ProjectionFacade {
    fun getOperation(workspaceId: UUID, operationId: UUID): OperationProjection
    fun getAccount(workspaceId: UUID, accountId: UUID): AccountProjection
    fun getAllAccounts(workspaceId: UUID, includeArchived: Boolean): List<AccountProjection>
    fun getCategory(workspaceId: UUID, categoryId: UUID): CategoryProjection
    fun getAllCategories(workspaceId: UUID, includeArchived: Boolean): List<CategoryProjection>
    fun getTransfer(workspaceId: UUID, transferId: UUID): Transfer
    fun getAllBalanceAnchors(workspaceId: UUID, accountId: UUID): List<BalanceAnchorProjection>
    fun getBalanceAnchor(workspaceId: UUID, accountId: UUID, anchorId: UUID): BalanceAnchorProjection
}

@Service
@Transactional
class ProjectionFacadeImpl(
    private val operationDAO: OperationProjectionDAO,
    private val accountDAO: AccountProjectionDAO,
    private val categoryDAO: CategoryProjectionDAO,
    private val workspaceService: WorkspaceService,
    private val identityProvider: IdentityProvider,
    private val transferLoader: TransferLoader,
    private val balanceAnchorDAO: BalanceAnchorProjectionDAO
) : ProjectionFacade {

    @Transactional(readOnly = true)
    override fun getOperation(
        workspaceId: UUID,
        operationId: UUID
    ): OperationProjection {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        return operationDAO.getById(workspace.id, operationId)
    }

    @Transactional(readOnly = true)
    override fun getAccount(
        workspaceId: UUID,
        accountId: UUID
    ): AccountProjection {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        return accountDAO.getById(workspace.id, accountId)
    }

    @Transactional(readOnly = true)
    override fun getAllAccounts(
        workspaceId: UUID,
        includeArchived: Boolean
    ): List<AccountProjection> {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        return accountDAO.getAllAccounts(workspace.id, includeArchived)
    }

    @Transactional(readOnly = true)
    override fun getCategory(
        workspaceId: UUID,
        categoryId: UUID
    ): CategoryProjection {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        return categoryDAO.getById(workspace.id, categoryId)
    }

    @Transactional(readOnly = true)
    override fun getAllCategories(
        workspaceId: UUID,
        includeArchived: Boolean
    ): List<CategoryProjection> {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        return categoryDAO.getAllCategories(workspace.id, includeArchived)
    }

    @Transactional(readOnly = true)
    override fun getTransfer(
        workspaceId: UUID,
        transferId: UUID
    ): Transfer {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        return transferLoader.loadTransfer(workspace.id, transferId)
    }

    override fun getAllBalanceAnchors(
        workspaceId: UUID,
        accountId: UUID
    ): List<BalanceAnchorProjection> {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        return balanceAnchorDAO.getAll(workspace.id, accountId)
    }

    override fun getBalanceAnchor(
        workspaceId: UUID,
        accountId: UUID,
        anchorId: UUID
    ): BalanceAnchorProjection {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        return balanceAnchorDAO.getById(workspace.id, accountId, anchorId)
    }

}