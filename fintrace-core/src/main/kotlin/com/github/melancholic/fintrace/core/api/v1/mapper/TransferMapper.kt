package com.github.melancholic.fintrace.core.api.v1.mapper

import com.github.melancholic.fintrace.core.api.v1.dto.CreateTransferRequest
import com.github.melancholic.fintrace.core.api.v1.dto.TransferLegResponse
import com.github.melancholic.fintrace.core.api.v1.dto.TransferResponse
import com.github.melancholic.fintrace.core.api.v1.dto.UpdateTransferRequest
import com.github.melancholic.fintrace.core.domain.command.CreateTransferCommand
import com.github.melancholic.fintrace.core.domain.command.ReviseTransferCommand
import com.github.melancholic.fintrace.core.domain.entity.Transfer
import com.github.melancholic.fintrace.core.domain.entity.TransferLeg
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.Named
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*

@Mapper(componentModel = "spring")
interface TransferMapper {

    fun toResponse(transfer: Transfer): TransferResponse = TransferResponse(
        id = transfer.id,
        workspaceId = transfer.workspaceId,
        occurredAt = transfer.occurredAt,
        recordedAt = transfer.recordedAt,
        comment = transfer.comment,
        source = toLegResponse(transfer.source),
        target = toLegResponse(transfer.target),
        rate = rate(transfer),
    )

    @Mapping(source = "amount", target = "amount", qualifiedByName = ["signedToUnsignedAmount"])
    fun toLegResponse(leg: TransferLeg): TransferLegResponse

    fun toCommand(workspaceId: UUID, request: CreateTransferRequest) = CreateTransferCommand(
        workspaceId = workspaceId,
        occurredAt = request.occurredAt,
        sourceAccountId = request.source.accountId,
        sourceAmount = request.source.amount,
        targetAccountId = request.target.accountId,
        targetAmount = request.target.amount,
        comment = request.comment,
    )

    fun toCommand(workspaceId: UUID, transferId: UUID, request: UpdateTransferRequest) = ReviseTransferCommand(
        workspaceId = workspaceId,
        transferId = transferId,
        occurredAt = request.occurredAt,
        sourceAccountId = request.source.accountId,
        sourceAmount = request.source.amount,
        targetAccountId = request.target.accountId,
        targetAmount = request.target.amount,
        comment = request.comment,
    )

    fun rate(transfer: Transfer): BigDecimal = transfer.target.amount.abs()
        .divide(transfer.source.amount.abs(), RATE_SCALE, RoundingMode.HALF_UP)

    @Named("signedToUnsignedAmount")
    fun signedToUnsignedAmount(amount: BigDecimal): BigDecimal = amount.abs()

    companion object {
        const val RATE_SCALE = 6
    }
}
