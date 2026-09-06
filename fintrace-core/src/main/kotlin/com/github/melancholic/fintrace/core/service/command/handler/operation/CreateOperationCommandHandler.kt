package com.github.melancholic.fintrace.core.service.command.handler.operation

import com.github.melancholic.fintrace.core.dao.EventsDAO
import com.github.melancholic.fintrace.core.domain.command.CreateOperationCommand
import com.github.melancholic.fintrace.core.domain.event.payload.OperationCreated
import com.github.melancholic.fintrace.core.domain.event.payload.OperationCreatedV1
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.service.projection.ProjectionApplier
import com.github.melancholic.fintrace.core.util.TimestampProvider
import com.github.melancholic.fintrace.core.util.UUIDGenerator
import com.github.melancholic.fintrace.core.validation.OperationValidationService
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

@Component
class CreateOperationCommandHandler(
    private val timestampProvider: TimestampProvider,
    private val uuidGenerator: UUIDGenerator,
    private val projectionApplier: ProjectionApplier,
    private val validationService: OperationValidationService,
    eventsDAO: EventsDAO,
) : AbstractOperationCommandHandler<CreateOperationCommand, OperationProjection, OperationCreated>(eventsDAO) {
    override val commandType: KClass<out CreateOperationCommand> = CreateOperationCommand::class

    override fun handle(command: CreateOperationCommand): OperationProjection {
        validationService.validate(command)
        val event = registerEvent(command)
        projectionApplier.apply(event.payload.projectionChange())
        return (event.payload as OperationCreated).projection()
    }

    override fun buildEventPayload(command: CreateOperationCommand): OperationCreated {
        return OperationCreatedV1(
            id = uuidGenerator.nextUUID(),
            workspaceId = command.workspaceId,
            amount = command.amount,
            occurredAt = command.occurredAt,
            recordedAt = timestampProvider.now()
        )
    }
}