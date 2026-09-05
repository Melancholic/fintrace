package com.github.melancholic.fintrace.core.service.command.handler.operation

import com.github.melancholic.fintrace.core.dao.EventsDAO
import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.command.CreateOperationCommand
import com.github.melancholic.fintrace.core.domain.entity.Workspace
import com.github.melancholic.fintrace.core.domain.event.payload.OperationCreated
import com.github.melancholic.fintrace.core.domain.event.payload.OperationCreatedV1
import com.github.melancholic.fintrace.core.security.IdentityProvider
import com.github.melancholic.fintrace.core.service.WorkspaceService
import com.github.melancholic.fintrace.core.util.TimestampProvider
import com.github.melancholic.fintrace.core.util.UUIDGenerator
import com.github.melancholic.fintrace.core.validation.OperationValidationService
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.util.*
import kotlin.reflect.KClass

@Component
class CreateOperationCommandHandler(
    private val timestampProvider: TimestampProvider,
    private val uuidGenerator: UUIDGenerator,
    private val operationProjectionDAO: OperationProjectionDAO,
    private val validationService: OperationValidationService,
    eventsDAO: EventsDAO,
    ) : AbstractOperationCommandHandler<CreateOperationCommand, UUID, OperationCreated>(eventsDAO) {
    override val commandType: KClass<out CreateOperationCommand> = CreateOperationCommand::class

    override fun handle(command: CreateOperationCommand): UUID {
        validationService.validate(command)
        val event = registerEvent(command)
        return operationProjectionDAO.createOrUpdate((event.payload as OperationCreated).asProjection())
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