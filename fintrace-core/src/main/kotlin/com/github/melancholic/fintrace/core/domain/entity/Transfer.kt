package com.github.melancholic.fintrace.core.domain.entity

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

data class Transfer(
    val id: UUID,
    val workspaceId: UUID,
    val occurredAt: LocalDateTime,
    val recordedAt: LocalDateTime,
    val comment: String?,
    val source: TransferLeg,
    val target: TransferLeg
)

data class TransferLeg(
    val operationId: UUID,
    val accountId: UUID,
    val currency: String,
    val amount: BigDecimal
)
