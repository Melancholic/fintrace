package com.github.melancholic.fintrace.core.validation

import com.github.melancholic.fintrace.core.config.ROOT_OTHERS_CAT_NAME
import com.github.melancholic.fintrace.core.dao.projection.CategoryProjectionDAO
import com.github.melancholic.fintrace.core.domain.command.CreateCategoryCommand
import com.github.melancholic.fintrace.core.domain.command.ReviseCategoryCommand
import com.github.melancholic.fintrace.core.domain.command.SetCategoryArchivedCommand
import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import com.github.melancholic.fintrace.core.domain.entity.CategorySystemCode
import com.github.melancholic.fintrace.core.domain.projection.CategoryProjection
import com.github.melancholic.fintrace.core.exception.ActionConflictException
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.exception.ValidationError
import com.github.melancholic.fintrace.core.validation.ValidationConstants.CATEGORY_NAME_PATTERN
import com.github.melancholic.fintrace.core.validation.ValidationConstants.MAX_ICON_LENGTH
import com.github.melancholic.fintrace.core.validation.ValidationConstants.MAX_NAME_LENGTH
import org.springframework.stereotype.Service
import java.util.*

interface CategoryValidationService {
    fun validate(createCommand: CreateCategoryCommand)
    fun validate(reviseCommand: ReviseCategoryCommand)
    fun validate(setArchivedCommand: SetCategoryArchivedCommand)
}

@Service
class CategoryValidationServiceImpl(
    private val categoryDAO: CategoryProjectionDAO
) : CategoryValidationService {

    override fun validate(createCommand: CreateCategoryCommand) {
        checkName(createCommand.name)
        checkIcon(createCommand.icon)

        if (createCommand.systemCode == null) {
            checkParentForCreation(createCommand.workspaceId, createCommand.parentId, createCommand.kind)
        }
    }

    override fun validate(reviseCommand: ReviseCategoryCommand) {
        checkName(reviseCommand.name)
        checkIcon(reviseCommand.icon)
        val category = requireExists(reviseCommand.workspaceId, reviseCommand.categoryId)
        checkSystem(category)
        checkArchived(category)
        checkParent(category.workspaceId, category, reviseCommand.parentId)
    }

    private fun checkArchived(category: CategoryProjection) {
        if (category.archived) {
            throw ActionConflictException("Can't modify archived category")
        }
    }

    override fun validate(setArchivedCommand: SetCategoryArchivedCommand) {
        val category = requireExists(setArchivedCommand.workspaceId, setArchivedCommand.categoryId)
        checkSystem(category)
    }

    private fun checkSystem(category: CategoryProjection) {
        if (category.systemCode != null) throw ActionConflictException("Can't modify system category")
    }

    private fun requireExists(workspaceId: UUID, categoryId: UUID): CategoryProjection {
        return categoryDAO.getByIdAsOptional(workspaceId, categoryId)
            .orElseThrow { NotFoundCategoryException(workspaceId, categoryId) }
    }

    private fun checkName(name: String) {
        if (name.isBlank()) {
            throw ValidationError("Category name must not be blank")
        }
        if (name.length > MAX_NAME_LENGTH) {
            throw ValidationError("Category name must be at most $MAX_NAME_LENGTH characters, was ${name.length}")
        }
        if (!NAME_REGEX.matches(name)) {
            throw ValidationError("Category name '$name' contains characters that are not permitted")
        }
    }

    private fun checkIcon(icon: String?) {
        if (icon != null && icon.length > MAX_ICON_LENGTH) {
            throw ValidationError("Category icon must be at most $MAX_ICON_LENGTH characters, was ${icon.length}")
        }
    }

    private fun checkParent(workspaceId: UUID, category: CategoryProjection, newParentId: UUID?) {
        newParentId ?: throw ValidationError("Category parent must not be null")

        if (category.id == newParentId) {
            throw ValidationError("Category id cannot be the same as the parent id")
        }
        val parent = categoryDAO.getByIdAsOptional(workspaceId, newParentId)
            .orElseThrow { NotFoundCategoryException(workspaceId, newParentId) }

        if (parent.archived) {
            throw ActionConflictException("Can't move under archived category")
        }

        if (newParentId in categoryDAO.findSubtreeIds(workspaceId, category.id)) {
            throw ActionConflictException("Can't move under current category")
        }

        if (category.kind != parent.kind) {
            throw CategoryKindConflictException()
        }

        if (parent.systemCode in CategorySystemCode.OTHERS) {
            throw CantNestCategoryUnderOthersException()
        }
    }

    private fun checkParentForCreation(
        workspaceId: UUID,
        parentId: UUID?,
        kind: CategoryKind
    ) {
        parentId ?: throw ValidationError("Category parent must not be null")

        val parent = categoryDAO.getByIdAsOptional(workspaceId, parentId)
            .orElseThrow { NotFoundCategoryException(workspaceId, parentId) }

        if (parent.archived) {
            throw ActionConflictException("Can't create category under archived category")
        }

        if (kind != parent.kind) {
            throw CategoryKindConflictException()
        }

        if (parent.systemCode in CategorySystemCode.OTHERS) {
            throw CantNestCategoryUnderOthersException()
        }
    }


    private class NotFoundCategoryException(workspaceId: UUID, categoryId: UUID) :
        NotFoundEntityException("Category not found (workspaceId='${workspaceId}', categoryId='${categoryId}')")

    private class CategoryKindConflictException :
        ActionConflictException("Category kind should be the same as the parent id")

    private class CantNestCategoryUnderOthersException :
        ActionConflictException("Can't nest a category under an $ROOT_OTHERS_CAT_NAME category")

    private companion object {
        val NAME_REGEX = Regex(CATEGORY_NAME_PATTERN)
    }
}
