package com.github.melancholic.fintrace.core.service.command.handler.balanceanchor

import com.github.melancholic.fintrace.core.dao.EventsDAO
import com.github.melancholic.fintrace.core.domain.command.CancelBalanceAnchorCommand
import com.github.melancholic.fintrace.core.domain.event.payload.BalanceAnchorCanceled
import com.github.melancholic.fintrace.core.domain.event.payload.BalanceAnchorCanceledV1
import com.github.melancholic.fintrace.core.service.projection.ProjectionApplier
import com.github.melancholic.fintrace.core.util.TimestampProvider
import com.github.melancholic.fintrace.core.validation.BalanceAnchorValidationService
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

@Component
class CancelBalanceAnchorCommandHandler(
    private val timestampProvider: TimestampProvider,
    private val projectionApplier: ProjectionApplier,
    private val validationService: BalanceAnchorValidationService,
    eventsDAO: EventsDAO,
) : AbstractBalanceAnchorCommandHandler<CancelBalanceAnchorCommand, Unit, BalanceAnchorCanceled>(
    eventsDAO
) {
    override val commandType: KClass<out CancelBalanceAnchorCommand> = CancelBalanceAnchorCommand::class

    override fun handle(command: CancelBalanceAnchorCommand) {
        validationService.validate(command)
        val event = registerEvent(command, buildEventPayload(command))
        projectionApplier.apply(event.payload.projectionChange())
    }

    private fun buildEventPayload(command: CancelBalanceAnchorCommand): BalanceAnchorCanceled {
        return BalanceAnchorCanceledV1(
            id = command.anchorId,
            workspaceId = command.workspaceId,
            accountId = command.accountId,
            recordedAt = timestampProvider.now()
        )
    }
}