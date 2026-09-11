package com.github.melancholic.fintrace.core.domain.command

import com.github.melancholic.fintrace.core.domain.entity.Transfer
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

sealed interface TransferCommand<R> : Command<R>

sealed interface ExistingTransferCommand<R> : TransferCommand<R> {
    val transferId: UUID
}

sealed interface TransferStateCommand<R> : TransferCommand<R>, TemporalCommand<R> {
    val sourceAccountId: UUID
    val sourceAmount: BigDecimal
    val targetAccountId: UUID
    val targetAmount: BigDecimal
    val comment: String?
}

data class CreateTransferCommand(
    override val workspaceId: UUID,
    override val occurredAt: LocalDateTime,
    override val sourceAccountId: UUID,
    override val sourceAmount: BigDecimal,
    override val targetAccountId: UUID,
    override val targetAmount: BigDecimal,
    override val comment: String?
) : TransferStateCommand<Transfer>, CreateCommand<Transfer>

data class ReviseTransferCommand(
    override val workspaceId: UUID,
    override val transferId: UUID,
    override val occurredAt: LocalDateTime,
    override val sourceAccountId: UUID,
    override val sourceAmount: BigDecimal,
    override val targetAccountId: UUID,
    override val targetAmount: BigDecimal,
    override val comment: String?
) : ExistingTransferCommand<Transfer>, TransferStateCommand<Transfer>, ReviseCommand<Transfer>

data class CancelTransferCommand(
    override val workspaceId: UUID,
    override val transferId: UUID
) : ExistingTransferCommand<Unit>, CancelCommand<Unit>
