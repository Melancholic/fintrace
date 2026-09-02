package com.github.melancholic.fintrace.core.service.command.handler.operation

import com.github.melancholic.fintrace.core.dao.EventsDAO
import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.command.CreateOperationCommand
import com.github.melancholic.fintrace.core.domain.event.payload.CreatedOperationEventPayloadV1
import com.github.melancholic.fintrace.core.domain.event.payload.EventPayload
import com.github.melancholic.fintrace.core.domain.event.payload.OperationEventPayload
import com.github.melancholic.fintrace.core.util.TimestampProvider
import com.github.melancholic.fintrace.core.util.UUIDGenerator
import com.github.melancholic.fintrace.core.validation.OperationValidationService
import org.springframework.stereotype.Component
import java.util.*
import kotlin.reflect.KClass

@Component
class CreateOperationCommandHandler(
    private val timestampProvider: TimestampProvider,
    private val uuidGenerator: UUIDGenerator,
    private val operationProjectionDAO: OperationProjectionDAO,
    validationService: OperationValidationService,
    eventsDAO: EventsDAO,
) : AbstractOperationCommandHandler<CreateOperationCommand, UUID>(
    validationService,
    eventsDAO
) {
    override val commandType: KClass<out CreateOperationCommand> = CreateOperationCommand::class

    override fun handle(command: CreateOperationCommand): UUID {
        validate(command)
        val event = registerEvent(command)
        return operationProjectionDAO.create((event.payload as OperationEventPayload).asProjection())
    }

    override fun buildEventPayload(command: CreateOperationCommand): EventPayload {
        return CreatedOperationEventPayloadV1(
            id = uuidGenerator.nextUUID(),
            workspaceId = command.workspaceId,
            amount = command.amount,
            occurredAt = command.occurredAt,
            recordedAt = timestampProvider.now()
        )
    }
}