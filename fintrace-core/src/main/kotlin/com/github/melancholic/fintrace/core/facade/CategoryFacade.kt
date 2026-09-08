package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.api.v1.dto.CreateCategoryRequest
import com.github.melancholic.fintrace.core.api.v1.dto.UpdateCategoryRequest
import com.github.melancholic.fintrace.core.dao.projection.CategoryProjectionDAO
import com.github.melancholic.fintrace.core.domain.command.CreateCategoryCommand
import com.github.melancholic.fintrace.core.domain.command.ReviseCategoryCommand
import com.github.melancholic.fintrace.core.domain.projection.CategoryProjection
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.security.IdentityProvider
import com.github.melancholic.fintrace.core.service.WorkspaceService
import com.github.melancholic.fintrace.core.service.command.CommandDispatcher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

interface CategoryFacade {
    fun create(workspaceId: UUID, request: CreateCategoryRequest): CategoryProjection
    fun reviseCategory(workspaceId: UUID, categoryId: UUID, request: UpdateCategoryRequest): CategoryProjection
}

@Service
@Transactional
class CategoryFacadeImpl(
    private val commandDispatcher: CommandDispatcher,
    private val workspaceService: WorkspaceService,
    private val identityProvider: IdentityProvider,
    private val categoryDao: CategoryProjectionDAO
) : CategoryFacade {

    override fun create(
        workspaceId: UUID,
        request: CreateCategoryRequest
    ): CategoryProjection {
        val userId = identityProvider.currentUserId()
        val workspace = workspaceService.requireWritable(userId, workspaceId)
        val parent = categoryDao.getByIdAsOptional(workspaceId, request.parentId)
            .orElseThrow { NotFoundEntityException("Couldn't resolve parent category: there is no category with id=${request.parentId}") }

        return commandDispatcher.dispatch(
            CreateCategoryCommand.custom(
                workspaceId = workspace.id,
                parentId = parent.id,
                kind = parent.kind,
                name = request.name,
                icon = request.icon
            )
        )
    }

    override fun reviseCategory(
        workspaceId: UUID,
        categoryId: UUID,
        request: UpdateCategoryRequest
    ): CategoryProjection {
        val userId = identityProvider.currentUserId()
        val workspace = workspaceService.requireWritable(userId, workspaceId)

        return commandDispatcher.dispatch(
            ReviseCategoryCommand(
                workspaceId = workspace.id,
                categoryId = categoryId,
                name = request.name,
                parentId = request.parentId,
                icon = request.icon
            )
        )
    }

}