package com.github.melancholic.fintrace.core.service.command.handler.transfer

import com.github.melancholic.fintrace.core.dao.EventsDAO
import com.github.melancholic.fintrace.core.domain.command.ReviseTransferCommand
import com.github.melancholic.fintrace.core.domain.entity.Transfer
import com.github.melancholic.fintrace.core.domain.event.payload.TransferLegPayload
import com.github.melancholic.fintrace.core.domain.event.payload.TransferRevised
import com.github.melancholic.fintrace.core.domain.event.payload.TransferRevisedV1
import com.github.melancholic.fintrace.core.domain.event.payload.TransferStateEventPayload
import com.github.melancholic.fintrace.core.exception.ApplicationException
import com.github.melancholic.fintrace.core.service.projection.ProjectionApplier
import com.github.melancholic.fintrace.core.service.transfer.TransferLoader
import com.github.melancholic.fintrace.core.util.TimestampProvider
import com.github.melancholic.fintrace.core.validation.TransferValidationService
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

@Component
class ReviseTransferCommandHandler(
    private val timestampProvider: TimestampProvider,
    private val projectionApplier: ProjectionApplier,
    private val validationService: TransferValidationService,
    private val transferLoader: TransferLoader,
    eventsDAO: EventsDAO,
) : AbstractTransferCommandHandler<ReviseTransferCommand, Transfer, TransferRevised>(
    eventsDAO
) {
    override val commandType: KClass<out ReviseTransferCommand> = ReviseTransferCommand::class

    override fun handle(command: ReviseTransferCommand): Transfer {
        validationService.validate(command)

        val current = currentPayload(command.workspaceId, command.transferId) as? TransferStateEventPayload
            ?: throw ApplicationException("Latest event for transfer '${command.transferId}' is not an transfer payload")

        val payload = TransferRevisedV1(
            id = command.transferId,
            workspaceId = command.workspaceId,
            occurredAt = command.occurredAt,
            recordedAt = timestampProvider.now(),
            comment = command.comment,
            externalRef = current.externalRef,
            source = TransferLegPayload(
                operationId = current.source.operationId,
                accountId = command.sourceAccountId,
                amount = command.sourceAmount.negate()
            ),
            target = TransferLegPayload(
                operationId = current.target.operationId,
                accountId = command.targetAccountId,
                amount = command.targetAmount
            )
        )

        val event = registerEvent(command, payload)
        projectionApplier.apply(event.payload.projectionChange())

        return transferLoader.loadTransfer(event.workspaceId, command.transferId)
    }
}