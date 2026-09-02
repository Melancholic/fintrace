package com.github.melancholic.fintrace.core.domain.command

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

sealed interface OperationCommand<R> : Command<R>

data class CreateOperationCommand(
    override val workspaceId: UUID,
    override val occurredAt: LocalDateTime,
    val amount: BigDecimal,
) : OperationCommand<UUID>, CreateCommand<UUID>

data class ReviseOperationCommand(
    override val workspaceId: UUID,
    override val occurredAt: LocalDateTime,
    // TODO: TBD
) : OperationCommand<Unit>, CreateCommand<Unit>

data class CancelOperationCommand(
    override val workspaceId: UUID,
    override val occurredAt: LocalDateTime,
    // TODO: TBD
) : OperationCommand<Unit>, CreateCommand<Unit>