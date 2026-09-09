package com.github.melancholic.fintrace.core.service.command.handler.category

import com.github.melancholic.fintrace.core.dao.EventsDAO
import com.github.melancholic.fintrace.core.domain.command.CreateCategoryCommand
import com.github.melancholic.fintrace.core.domain.event.payload.CategoryCreated
import com.github.melancholic.fintrace.core.domain.event.payload.CategoryCreatedV1
import com.github.melancholic.fintrace.core.domain.projection.CategoryProjection
import com.github.melancholic.fintrace.core.service.projection.ProjectionApplier
import com.github.melancholic.fintrace.core.util.TimestampProvider
import com.github.melancholic.fintrace.core.util.UUIDGenerator
import com.github.melancholic.fintrace.core.validation.CategoryValidationService
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

@Component
class CreateCategoryCommandHandler(
    private val timestampProvider: TimestampProvider,
    private val uuidGenerator: UUIDGenerator,
    private val projectionApplier: ProjectionApplier,
    private val validationService: CategoryValidationService,
    eventsDAO: EventsDAO,
) : AbstractCategoryCommandHandler<CreateCategoryCommand, CategoryProjection, CategoryCreated>(eventsDAO) {
    override val commandType: KClass<out CreateCategoryCommand> = CreateCategoryCommand::class

    override fun handle(command: CreateCategoryCommand): CategoryProjection {
        validationService.validate(command)
        val event = registerEvent(command, buildEventPayload(command))
        projectionApplier.apply(event.payload.projectionChange())
        return (event.payload as CategoryCreated).projection()
    }

    private fun buildEventPayload(command: CreateCategoryCommand): CategoryCreated {
        return CategoryCreatedV1(
            id = uuidGenerator.nextUUID(),
            workspaceId = command.workspaceId,
            name = command.name,
            icon = command.icon,
            parentId = command.parentId,
            kind = command.kind,
            archived = false,
            systemCode = command.systemCode,
            recordedAt = timestampProvider.now()
        )
    }
}