package com.github.melancholic.fintrace.core.api.v1.dto

import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import org.hibernate.validator.constraints.Length
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

data class CreateOperationRequest(
    @Positive
    @NotNull
    val amount: BigDecimal,
    @NotNull
    val occurredAt: LocalDateTime,
    @NotNull
    val kind: OperationKind,
    @NotNull
    val accountId: UUID,
    val categoryId: UUID?,
    @Length(max = 255)
    val comment: String?
)

data class UpdateOperationRequest(
    @Positive
    val amount: BigDecimal,
    @NotNull
    val occurredAt: LocalDateTime,
    @NotNull
    val kind: OperationKind,
    @NotNull
    val accountId: UUID,
    val categoryId: UUID,
    @Length(max = 255)
    val comment: String?
)

data class OperationResponse(
    val id: UUID,
    val workspaceId: UUID,
    val accountId: UUID,
    val categoryId: UUID,
    val transferId: UUID?,
    val amount: BigDecimal,
    val occurredAt: LocalDateTime,
    val recordedAt: LocalDateTime,
    val kind: OperationKind,
    val counterpartId: UUID?,
    val comment: String?,
    val externalRef: String?
)
