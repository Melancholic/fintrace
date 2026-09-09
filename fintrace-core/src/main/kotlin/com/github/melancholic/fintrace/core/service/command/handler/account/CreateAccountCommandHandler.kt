package com.github.melancholic.fintrace.core.service.command.handler.account

import com.github.melancholic.fintrace.core.dao.EventsDAO
import com.github.melancholic.fintrace.core.domain.command.CreateAccountCommand
import com.github.melancholic.fintrace.core.domain.event.payload.AccountCreated
import com.github.melancholic.fintrace.core.domain.event.payload.AccountCreatedV1
import com.github.melancholic.fintrace.core.domain.projection.AccountProjection
import com.github.melancholic.fintrace.core.service.projection.ProjectionApplier
import com.github.melancholic.fintrace.core.util.TimestampProvider
import com.github.melancholic.fintrace.core.util.UUIDGenerator
import com.github.melancholic.fintrace.core.validation.AccountValidationService
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

@Component
class CreateAccountCommandHandler(
    private val timestampProvider: TimestampProvider,
    private val uuidGenerator: UUIDGenerator,
    private val projectionApplier: ProjectionApplier,
    private val validationService: AccountValidationService,
    eventsDAO: EventsDAO,
) : AbstractAccountCommandHandler<CreateAccountCommand, AccountProjection, AccountCreated>(eventsDAO) {
    override val commandType: KClass<out CreateAccountCommand> = CreateAccountCommand::class

    override fun handle(command: CreateAccountCommand): AccountProjection {
        validationService.validate(command)
        val event = registerEvent(command, buildEventPayload(command))
        projectionApplier.apply(event.payload.projectionChange())
        return (event.payload as AccountCreated).projection()
    }

    private fun buildEventPayload(command: CreateAccountCommand): AccountCreated {
        return AccountCreatedV1(
            id = uuidGenerator.nextUUID(),
            workspaceId = command.workspaceId,
            name = command.name,
            currency = command.currency,
            icon = command.icon,
            archived = false,
            recordedAt = timestampProvider.now()
        )
    }
}