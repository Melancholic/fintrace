package com.github.melancholic.fintrace.core.domain.projection

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

data class BalanceAnchorProjection(
    override val id: UUID,
    override val workspaceId: UUID,
    val accountId: UUID,
    val value: BigDecimal,
    override val occurredAt: LocalDateTime,
    override val recordedAt: LocalDateTime
) : TemporalProjection