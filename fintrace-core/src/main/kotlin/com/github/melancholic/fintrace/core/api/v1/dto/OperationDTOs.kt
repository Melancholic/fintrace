package com.github.melancholic.fintrace.core.api.v1.dto

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

data class OperationRequest(
    val amount: BigDecimal,
    val occurredAt: LocalDateTime,
)

data class CreateOperationResponse(
    val id: UUID,
)

data class OperationResponse(
    val id: UUID,
    val amount: BigDecimal,
    val occurredAt: LocalDateTime,
    val recordedAt: LocalDateTime,
)
