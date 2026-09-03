package com.github.melancholic.fintrace.core.service.command.handler.operation

import com.github.melancholic.fintrace.core.dao.EventsDAO
import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.command.CancelOperationCommand
import com.github.melancholic.fintrace.core.domain.event.payload.OperationCanceled
import com.github.melancholic.fintrace.core.domain.event.payload.OperationCanceledV1
import com.github.melancholic.fintrace.core.util.TimestampProvider
import com.github.melancholic.fintrace.core.validation.OperationValidationService
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

@Component
class CancelOperationCommandHandler(
    private val timestampProvider: TimestampProvider,
    private val operationProjectionDAO: OperationProjectionDAO,
    private val validationService: OperationValidationService,
    eventsDAO: EventsDAO,
) : AbstractOperationCommandHandler<CancelOperationCommand, Unit, OperationCanceled>(
    eventsDAO
) {
    override val commandType: KClass<out CancelOperationCommand> = CancelOperationCommand::class

    override fun handle(command: CancelOperationCommand) {
        validationService.validate(command)
        val event = registerEvent(command)
        operationProjectionDAO.remove(event.workspaceId, event.entityId)
    }

    override fun buildEventPayload(command: CancelOperationCommand): OperationCanceled {
        return OperationCanceledV1(
            id = command.operationId,
            workspaceId = command.workspaceId,
            occurredAt = command.occurredAt,
            recordedAt = timestampProvider.now()
        )
    }
}