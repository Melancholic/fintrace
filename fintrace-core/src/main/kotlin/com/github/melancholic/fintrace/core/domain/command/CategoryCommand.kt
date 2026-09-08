package com.github.melancholic.fintrace.core.domain.command

import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import com.github.melancholic.fintrace.core.domain.entity.CategorySystemCode
import com.github.melancholic.fintrace.core.domain.projection.CategoryProjection
import java.util.*

sealed interface CategoryCommand<R> : Command<R>

sealed interface ExistingCategoryCommand<R> : CategoryCommand<R> {
    val categoryId: UUID
}

data class CreateCategoryCommand(
    override val workspaceId: UUID,
    val parentId: UUID?,
    val name: String,
    val kind: CategoryKind,
    val systemCode: CategorySystemCode?,
    val icon: String?
) : CategoryCommand<CategoryProjection>, CreateCommand<CategoryProjection> {

    companion object {

        fun custom(
            workspaceId: UUID,
            kind: CategoryKind,
            name: String,
            parentId: UUID,
            icon: String?
        ): CreateCategoryCommand {
            return CreateCategoryCommand(
                workspaceId = workspaceId,
                parentId = parentId,
                kind = kind,
                systemCode = null,
                name = name,
                icon = icon
            )
        }

        fun system(
            workspaceId: UUID,
            kind: CategoryKind,
            name: String,
            icon: String,
            systemCode: CategorySystemCode,
            parentId: UUID?
        ): CreateCategoryCommand {
            return CreateCategoryCommand(
                workspaceId = workspaceId,
                parentId = parentId,
                kind = kind,
                systemCode = systemCode,
                name = name,
                icon = icon
            )
        }
    }
}

data class ReviseCategoryCommand(
    override val workspaceId: UUID,
    override val categoryId: UUID,
    val parentId: UUID?,
    val name: String,
    val icon: String?,
) : ExistingCategoryCommand<CategoryProjection>, ReviseCommand<CategoryProjection>

data class SetCategoryArchivedCommand(
    override val workspaceId: UUID,
    override val categoryId: UUID,
    val archived: Boolean
) : ExistingCategoryCommand<CategoryProjection>, ReviseCommand<CategoryProjection>