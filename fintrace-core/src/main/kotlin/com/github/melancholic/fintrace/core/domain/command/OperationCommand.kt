package com.github.melancholic.fintrace.core.domain.command

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

sealed interface OperationCommand<R> : Command<R>

sealed interface ExistingOperationCommand<R> : OperationCommand<R> {
    val operationId: UUID
}

data class CreateOperationCommand(
    override val workspaceId: UUID,
    override val occurredAt: LocalDateTime,
    val amount: BigDecimal,
) : OperationCommand<UUID>, CreateCommand<UUID>

data class ReviseOperationCommand(
    override val workspaceId: UUID,
    override val operationId: UUID,
    override val occurredAt: LocalDateTime,
    val amount: BigDecimal,
) : ExistingOperationCommand<Unit>, ReviseCommand<Unit>

data class CancelOperationCommand(
    override val workspaceId: UUID,
    override val operationId: UUID,
    override val occurredAt: LocalDateTime,
) : ExistingOperationCommand<Unit>, CancelCommand<Unit>