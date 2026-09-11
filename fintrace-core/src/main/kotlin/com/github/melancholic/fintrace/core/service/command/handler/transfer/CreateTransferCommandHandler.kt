package com.github.melancholic.fintrace.core.service.command.handler.transfer

import com.github.melancholic.fintrace.core.dao.EventsDAO
import com.github.melancholic.fintrace.core.domain.command.CreateTransferCommand
import com.github.melancholic.fintrace.core.domain.entity.Transfer
import com.github.melancholic.fintrace.core.domain.event.payload.TransferCreated
import com.github.melancholic.fintrace.core.domain.event.payload.TransferCreatedV1
import com.github.melancholic.fintrace.core.domain.event.payload.TransferLegPayload
import com.github.melancholic.fintrace.core.service.projection.ProjectionApplier
import com.github.melancholic.fintrace.core.service.transfer.TransferLoader
import com.github.melancholic.fintrace.core.util.TimestampProvider
import com.github.melancholic.fintrace.core.util.UUIDGenerator
import com.github.melancholic.fintrace.core.validation.TransferValidationService
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

@Component
class CreateTransferCommandHandler(
    private val timestampProvider: TimestampProvider,
    private val projectionApplier: ProjectionApplier,
    private val validationService: TransferValidationService,
    private val uuidGenerator: UUIDGenerator,
    private val transferLoader: TransferLoader,
    eventsDAO: EventsDAO,
) : AbstractTransferCommandHandler<CreateTransferCommand, Transfer, TransferCreated>(
    eventsDAO
) {
    override val commandType: KClass<out CreateTransferCommand> = CreateTransferCommand::class

    override fun handle(command: CreateTransferCommand): Transfer {
        validationService.validate(command)
        val transferId = uuidGenerator.nextUUID()
        val payload = TransferCreatedV1(
            id = transferId,
            workspaceId = command.workspaceId,
            occurredAt = command.occurredAt,
            recordedAt = timestampProvider.now(),
            comment = command.comment,
            externalRef = null,
            source = TransferLegPayload(
                operationId = uuidGenerator.nextUUID(),
                accountId = command.sourceAccountId,
                amount = command.sourceAmount.negate(),

                ),
            target = TransferLegPayload(
                operationId = uuidGenerator.nextUUID(),
                accountId = command.targetAccountId,
                amount = command.targetAmount,
            )
        )

        val event = registerEvent(command, payload)
        projectionApplier.apply(event.payload.projectionChange())

        return transferLoader.loadTransfer(event.workspaceId, transferId)
    }

}