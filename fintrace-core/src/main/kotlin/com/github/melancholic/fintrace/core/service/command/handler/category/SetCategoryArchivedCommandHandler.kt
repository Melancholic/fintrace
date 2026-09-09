package com.github.melancholic.fintrace.core.service.command.handler.category

import com.github.melancholic.fintrace.core.dao.EventsDAO
import com.github.melancholic.fintrace.core.dao.projection.CategoryProjectionDAO
import com.github.melancholic.fintrace.core.domain.command.SetCategoryArchivedCommand
import com.github.melancholic.fintrace.core.domain.event.payload.CategoryEventPayload
import com.github.melancholic.fintrace.core.domain.event.payload.CategoryRevised
import com.github.melancholic.fintrace.core.domain.event.payload.CategoryRevisedV1
import com.github.melancholic.fintrace.core.domain.projection.CategoryProjection
import com.github.melancholic.fintrace.core.exception.ApplicationException
import com.github.melancholic.fintrace.core.service.projection.ProjectionApplier
import com.github.melancholic.fintrace.core.service.projection.ProjectionChange
import com.github.melancholic.fintrace.core.util.TimestampProvider
import com.github.melancholic.fintrace.core.validation.CategoryValidationService
import org.springframework.stereotype.Component
import java.util.*
import kotlin.reflect.KClass

@Component
class SetCategoryArchivedCommandHandler(
    private val timestampProvider: TimestampProvider,
    private val projectionApplier: ProjectionApplier,
    private val validationService: CategoryValidationService,
    private val categoryDAO: CategoryProjectionDAO,
    eventsDAO: EventsDAO,
) : AbstractCategoryCommandHandler<SetCategoryArchivedCommand, CategoryProjection, CategoryRevised>(eventsDAO) {

    override val commandType: KClass<out SetCategoryArchivedCommand> = SetCategoryArchivedCommand::class

    override fun handle(command: SetCategoryArchivedCommand): CategoryProjection {
        validationService.validate(command)

        if (command.archived) {
            // Archive whole subtree
            val forArchive = categoryDAO.findSubtreeIds(command.workspaceId, command.categoryId)
                .map { archive(command, it) }

            val category = forArchive.singleOrNull { it.id == command.categoryId }
                ?: throw ApplicationException("Category '${command.categoryId}' was not part of its own subtree")

            projectionApplier.apply(ProjectionChange.Upsert(forArchive))

            return category
        } else {
            // Restore only specific category
            val category = restore(command, command.categoryId)
            projectionApplier.apply(ProjectionChange.Upsert(listOf(category)))
            return category
        }
    }


    private fun archive(command: SetCategoryArchivedCommand, categoryId: UUID): CategoryProjection {
        val current = currentState(command.workspaceId, categoryId)
        if (current.archived) {
            // Already archived - just return current state
            return current.projection()
        }

        val event = registerEvent(command, payload(current, command.archived))
        return (event.payload as CategoryRevised).projection()
    }

    private fun restore(command: SetCategoryArchivedCommand, categoryId: UUID): CategoryProjection {
        val current = currentState(command.workspaceId, categoryId)
        if (!current.archived) {
            // Already not archived - just return current state
            return current.projection()
        }

        val event = registerEvent(command, payload(current, command.archived))
        return (event.payload as CategoryRevised).projection()
    }

    private fun currentState(workspaceId: UUID, categoryId: UUID): CategoryEventPayload =
        currentPayload(workspaceId, categoryId) as? CategoryEventPayload
            ?: throw ApplicationException("Latest event for category '$categoryId' is not a category payload")

    private fun payload(current: CategoryEventPayload, archived: Boolean) = CategoryRevisedV1(
        id = current.id,
        workspaceId = current.workspaceId,
        parentId = current.parentId,
        name = current.name,
        kind = current.kind,
        icon = current.icon,
        systemCode = current.systemCode,
        archived = archived,
        recordedAt = timestampProvider.now()
    )

    private fun buildEventPayload(command: SetCategoryArchivedCommand): CategoryRevised =
        payload(currentState(command.workspaceId, command.categoryId), command.archived)
}
