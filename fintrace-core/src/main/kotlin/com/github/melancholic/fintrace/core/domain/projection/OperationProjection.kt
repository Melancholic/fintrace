package com.github.melancholic.fintrace.core.domain.projection

import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

data class OperationProjection(
    override val id: UUID,
    override val workspaceId: UUID,
    val amount: BigDecimal,
    val kind: OperationKind,
    val accountId: UUID,
    val categoryId: UUID,
    val transferId: UUID?,
    val counterpartId: UUID?,
    val comment: String?,
    val externalRef: String?,
    override val occurredAt: LocalDateTime,
    override val recordedAt: LocalDateTime
) : TemporalProjection