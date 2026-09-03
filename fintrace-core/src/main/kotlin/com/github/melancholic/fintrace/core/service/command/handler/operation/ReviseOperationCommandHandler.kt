package com.github.melancholic.fintrace.core.service.command.handler.operation

import com.github.melancholic.fintrace.core.dao.EventsDAO
import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.command.ReviseOperationCommand
import com.github.melancholic.fintrace.core.domain.event.payload.OperationRevised
import com.github.melancholic.fintrace.core.domain.event.payload.OperationRevisedV1
import com.github.melancholic.fintrace.core.util.TimestampProvider
import com.github.melancholic.fintrace.core.validation.OperationValidationService
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

@Component
class ReviseOperationCommandHandler(
    private val timestampProvider: TimestampProvider,
    private val operationProjectionDAO: OperationProjectionDAO,
    private val validationService: OperationValidationService,
    eventsDAO: EventsDAO,
) : AbstractOperationCommandHandler<ReviseOperationCommand, Unit, OperationRevised>(
    eventsDAO
) {
    override val commandType: KClass<out ReviseOperationCommand> = ReviseOperationCommand::class

    override fun handle(command: ReviseOperationCommand) {
        validationService.validate(command)
        val event = registerEvent(command)
        operationProjectionDAO.createOrUpdate((event.payload as OperationRevised).asProjection())
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