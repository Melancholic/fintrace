package com.github.melancholic.fintrace.core.domain.event.payload

import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.service.projection.ProjectionChange
import java.math.BigDecimal
import java.util.*

sealed interface OperationEventPayload : EventPayload

sealed interface BalanceOperationEventPayload : OperationEventPayload, TemporalEventPayload {
    val amount: BigDecimal
    val categoryId: UUID
    val kind: OperationKind
    val accountId: UUID
    val transferId: UUID?
    val counterpartId: UUID?
    val externalRef: String?
    val comment: String?

    override fun projectionChange(): ProjectionChange = ProjectionChange
        .Upsert(listOf(projection()))

    fun projection(): OperationProjection = OperationProjection(
        id = id,
        workspaceId = workspaceId,
        amount = amount,
        kind = kind,
        accountId = accountId,
        categoryId = categoryId,
        transferId = transferId,
        counterpartId = counterpartId,
        comment = comment,
        externalRef = externalRef,
        occurredAt = occurredAt,
        recordedAt = recordedAt
    )
}