package com.github.melancholic.fintrace.core.service.command.handler.account

import com.github.melancholic.fintrace.core.dao.EventsDAO
import com.github.melancholic.fintrace.core.domain.command.SetAccountArchivedCommand
import com.github.melancholic.fintrace.core.domain.event.payload.AccountEventPayload
import com.github.melancholic.fintrace.core.domain.event.payload.AccountRevised
import com.github.melancholic.fintrace.core.domain.event.payload.AccountRevisedV1
import com.github.melancholic.fintrace.core.domain.projection.AccountProjection
import com.github.melancholic.fintrace.core.exception.ApplicationException
import com.github.melancholic.fintrace.core.service.projection.ProjectionApplier
import com.github.melancholic.fintrace.core.util.TimestampProvider
import com.github.melancholic.fintrace.core.validation.AccountValidationService
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

@Component
class SetAccountArchivedCommandHandler(
    private val timestampProvider: TimestampProvider,
    private val projectionApplier: ProjectionApplier,
    private val validationService: AccountValidationService,
    eventsDAO: EventsDAO,
) : AbstractAccountCommandHandler<SetAccountArchivedCommand, AccountProjection, AccountRevised>(
    eventsDAO
) {
    override val commandType: KClass<out SetAccountArchivedCommand> = SetAccountArchivedCommand::class

    override fun handle(command: SetAccountArchivedCommand): AccountProjection {
        validationService.validate(command)
        val event = registerEvent(command, buildEventPayload(command))
        projectionApplier.apply(event.payload.projectionChange())
        return (event.payload as AccountRevised).projection()
    }

    private fun buildEventPayload(command: SetAccountArchivedCommand): AccountRevised {
        val current = currentPayload(command.workspaceId, command.accountId) as? AccountEventPayload
            ?: throw ApplicationException("Latest event for account '${command.accountId}' is not an account payload")

        return AccountRevisedV1(
            id = command.accountId,
            workspaceId = command.workspaceId,
            archived = command.archived,
            recordedAt = timestampProvider.now(),
            name = current.name,
            currency = current.currency,
            icon = current.icon
        )
    }
}