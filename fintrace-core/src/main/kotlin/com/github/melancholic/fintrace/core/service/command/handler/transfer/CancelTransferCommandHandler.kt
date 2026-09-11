package com.github.melancholic.fintrace.core.service.command.handler.transfer

import com.github.melancholic.fintrace.core.dao.EventsDAO
import com.github.melancholic.fintrace.core.domain.command.CancelTransferCommand
import com.github.melancholic.fintrace.core.domain.event.payload.TransferCanceled
import com.github.melancholic.fintrace.core.domain.event.payload.TransferCanceledV1
import com.github.melancholic.fintrace.core.domain.event.payload.TransferStateEventPayload
import com.github.melancholic.fintrace.core.exception.ApplicationException
import com.github.melancholic.fintrace.core.service.projection.ProjectionApplier
import com.github.melancholic.fintrace.core.util.TimestampProvider
import com.github.melancholic.fintrace.core.validation.TransferValidationService
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

@Component
class CancelTransferCommandHandler(
    private val timestampProvider: TimestampProvider,
    private val projectionApplier: ProjectionApplier,
    private val validationService: TransferValidationService,
    eventsDAO: EventsDAO,
) : AbstractTransferCommandHandler<CancelTransferCommand, Unit, TransferCanceled>(
    eventsDAO
) {
    override val commandType: KClass<out CancelTransferCommand> = CancelTransferCommand::class

    override fun handle(command: CancelTransferCommand) {
        validationService.validate(command)

        val current = currentPayload(command.workspaceId, command.transferId) as? TransferStateEventPayload
            ?: throw ApplicationException("Latest event for transfer '${command.transferId}' is not an transfer payload")

        val payload = TransferCanceledV1(
            id = command.transferId,
            workspaceId = command.workspaceId,
            legIds = setOf(current.source.operationId, current.target.operationId),
            recordedAt = timestampProvider.now()
        )

        val event = registerEvent(command, payload)
        projectionApplier.apply(event.payload.projectionChange())
    }
}