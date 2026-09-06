package com.github.melancholic.fintrace.core.service.command.handler.operation

import com.github.melancholic.fintrace.core.dao.EventsDAO
import com.github.melancholic.fintrace.core.domain.command.ReviseOperationCommand
import com.github.melancholic.fintrace.core.domain.event.payload.OperationRevised
import com.github.melancholic.fintrace.core.domain.event.payload.OperationRevisedV1
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.service.projection.ProjectionApplier
import com.github.melancholic.fintrace.core.util.TimestampProvider
import com.github.melancholic.fintrace.core.validation.OperationValidationService
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

@Component
class ReviseOperationCommandHandler(
    private val timestampProvider: TimestampProvider,
    private val projectionApplier: ProjectionApplier,
    private val validationService: OperationValidationService,
    eventsDAO: EventsDAO,
) : AbstractOperationCommandHandler<ReviseOperationCommand, OperationProjection, OperationRevised>(
    eventsDAO
) {
    override val commandType: KClass<out ReviseOperationCommand> = ReviseOperationCommand::class

    override fun handle(command: ReviseOperationCommand): OperationProjection {
        validationService.validate(command)
        val event = registerEvent(command)
        projectionApplier.apply(event.payload.projectionChange())
        return (event.payload as OperationRevised).projection()
    }

    override fun buildEventPayload(command: ReviseOperationCommand): OperationRevised {
        return OperationRevisedV1(
            id = command.operationId,
            workspaceId = command.workspaceId,
            amount = command.amount,
            occurredAt = command.occurredAt,
            recordedAt = timestampProvider.now()
        )
    }
}