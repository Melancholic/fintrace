package com.github.melancholic.fintrace.core.api.v1.dto

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

data class CreateBalanceAnchorRequest(
    val value: BigDecimal
)

data class BalanceAnchorResponse(
    val id: UUID,
    val workspaceId: UUID,
    val accountId: UUID,
    val value: BigDecimal,
    val occurredAt: LocalDateTime,
    val recordedAt: LocalDateTime
)