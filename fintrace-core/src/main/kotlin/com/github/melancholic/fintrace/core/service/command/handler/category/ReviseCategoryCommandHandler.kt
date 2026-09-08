package com.github.melancholic.fintrace.core.service.command.handler.category

import com.github.melancholic.fintrace.core.dao.EventsDAO
import com.github.melancholic.fintrace.core.domain.command.ReviseCategoryCommand
import com.github.melancholic.fintrace.core.domain.event.payload.CategoryEventPayload
import com.github.melancholic.fintrace.core.domain.event.payload.CategoryRevised
import com.github.melancholic.fintrace.core.domain.event.payload.CategoryRevisedV1
import com.github.melancholic.fintrace.core.domain.projection.CategoryProjection
import com.github.melancholic.fintrace.core.exception.ApplicationException
import com.github.melancholic.fintrace.core.service.projection.ProjectionApplier
import com.github.melancholic.fintrace.core.util.TimestampProvider
import com.github.melancholic.fintrace.core.validation.CategoryValidationService
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

@Component
class ReviseCategoryCommandHandler(
    private val timestampProvider: TimestampProvider,
    private val projectionApplier: ProjectionApplier,
    private val validationService: CategoryValidationService,
    eventsDAO: EventsDAO,
) : AbstractCategoryCommandHandler<ReviseCategoryCommand, CategoryProjection, CategoryRevised>(eventsDAO) {
    override val commandType: KClass<out ReviseCategoryCommand> = ReviseCategoryCommand::class

    override fun handle(command: ReviseCategoryCommand): CategoryProjection {
        validationService.validate(command)
        val event = registerEvent(command)
        projectionApplier.apply(event.payload.projectionChange())
        return (event.payload as CategoryRevised).projection()
    }

    override fun buildEventPayload(command: ReviseCategoryCommand): CategoryRevised {
        val current = currentPayload(command.workspaceId, command.categoryId) as? CategoryEventPayload
            ?: throw ApplicationException("Latest event for category '${command.categoryId}' is not an category payload")

        return CategoryRevisedV1(
            id = current.id,
            workspaceId = command.workspaceId,
            name = command.name,
            icon = command.icon,
            parentId = command.parentId,
            kind = current.kind,
            archived = current.archived,
            systemCode = current.systemCode,
            recordedAt = timestampProvider.now()
        )
    }
}