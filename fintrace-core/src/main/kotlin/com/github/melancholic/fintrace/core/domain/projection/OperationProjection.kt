package com.github.melancholic.fintrace.core.domain.projection

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

data class OperationProjection(
    override val id: UUID,
    override val workspaceId: UUID,
    val amount: BigDecimal,
    override val occurredAt: LocalDateTime,
    override val recordedAt: LocalDateTime,
) : Projection