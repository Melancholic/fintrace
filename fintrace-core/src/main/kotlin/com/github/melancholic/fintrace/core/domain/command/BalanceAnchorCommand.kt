package com.github.melancholic.fintrace.core.domain.command

import com.github.melancholic.fintrace.core.domain.projection.BalanceAnchorProjection
import java.math.BigDecimal
import java.util.*

sealed interface BalanceAnchorCommand<R> : Command<R> {
    val accountId: UUID
}

sealed interface ExistingBalanceAnchorCommand<R> : BalanceAnchorCommand<R> {
    val anchorId: UUID
}

data class CreateBalanceAnchorCommand(
    override val workspaceId: UUID,
    override val accountId: UUID,
    val value: BigDecimal,
) : BalanceAnchorCommand<BalanceAnchorProjection>, CreateCommand<BalanceAnchorProjection>

data class CancelBalanceAnchorCommand(
    override val workspaceId: UUID,
    override val accountId: UUID,
    override val anchorId: UUID
) : ExistingBalanceAnchorCommand<Unit>, CancelCommand<Unit>