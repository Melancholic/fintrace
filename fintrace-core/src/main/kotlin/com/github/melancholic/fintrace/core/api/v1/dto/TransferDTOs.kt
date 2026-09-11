package com.github.melancholic.fintrace.core.api.v1.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import org.hibernate.validator.constraints.Length
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

data class CreateTransferRequest(
    @NotNull
    val occurredAt: LocalDateTime,
    @Valid
    @NotNull
    val source: TransferLegRequest,
    @Valid
    @NotNull
    val target: TransferLegRequest,
    @Length(max = 255)
    val comment: String?
)

data class UpdateTransferRequest(
    @NotNull
    val occurredAt: LocalDateTime,
    @Valid
    @NotNull
    val source: TransferLegRequest,
    @Valid
    @NotNull
    val target: TransferLegRequest,
    @Length(max = 255)
    val comment: String?
)

data class TransferLegRequest(
    @NotNull
    val accountId: UUID,
    @Positive
    @NotNull
    val amount: BigDecimal
)

data class TransferResponse(
    val id: UUID,
    val workspaceId: UUID,
    val occurredAt: LocalDateTime,
    val recordedAt: LocalDateTime,
    val comment: String?,
    val source: TransferLegResponse,
    val target: TransferLegResponse,
    val rate: BigDecimal
)

data class TransferLegResponse(
    val operationId: UUID,
    val accountId: UUID,
    val currency: String,
    val amount: BigDecimal
)