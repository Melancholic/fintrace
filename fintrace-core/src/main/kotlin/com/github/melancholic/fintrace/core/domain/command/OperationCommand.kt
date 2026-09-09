package com.github.melancholic.fintrace.core.domain.command

import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

sealed interface OperationCommand<R> : Command<R>

sealed interface ExistingOperationCommand<R> : OperationCommand<R> {
    val operationId: UUID
}

sealed interface OperationStateCommand<R> : OperationCommand<R>, TemporalCommand<R> {
    val amount: BigDecimal
    val accountId: UUID
    val kind: OperationKind
    val categoryId: UUID?
    val comment: String?
}

data class CreateOperationCommand(
    override val workspaceId: UUID,
    override val occurredAt: LocalDateTime,
    override val amount: BigDecimal,
    override val accountId: UUID,
    override val kind: OperationKind,
    override val categoryId: UUID?,
    override val comment: String?
) : OperationStateCommand<OperationProjection>,
    CreateCommand<OperationProjection>,
    TemporalCommand<OperationProjection>

data class ReviseOperationCommand(
    override val workspaceId: UUID,
    override val operationId: UUID,
    override val occurredAt: LocalDateTime,
    override val amount: BigDecimal,
    override val accountId: UUID,
    override val kind: OperationKind,
    override val categoryId: UUID,
    override val comment: String?
) : ExistingOperationCommand<OperationProjection>,
    OperationStateCommand<OperationProjection>,
    ReviseCommand<OperationProjection>

data class CancelOperationCommand(
    override val workspaceId: UUID,
    override val operationId: UUID,
) : ExistingOperationCommand<Unit>, CancelCommand<Unit>