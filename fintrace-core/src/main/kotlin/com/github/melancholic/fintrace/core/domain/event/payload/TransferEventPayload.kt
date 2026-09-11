package com.github.melancholic.fintrace.core.domain.event.payload

import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.service.projection.ProjectionChange
import java.math.BigDecimal
import java.util.*

sealed interface TransferEventPayload : EventPayload

sealed interface TransferStateEventPayload : TransferEventPayload, TemporalEventPayload {
    val source: TransferLegPayload
    val target: TransferLegPayload
    val comment: String?
    val externalRef: String?

    override fun projectionChange(): ProjectionChange = ProjectionChange.Upsert(
        listOf(
            projection(source, counterpart = target),
            projection(target, counterpart = source)
        )
    )

    fun projection(leg: TransferLegPayload, counterpart: TransferLegPayload) = OperationProjection(
        id = leg.operationId,
        workspaceId = workspaceId,
        amount = leg.amount,
        kind = OperationKind.TRANSFER,
        accountId = leg.accountId,
        categoryId = null,
        transferId = id,
        counterpartId = counterpart.operationId,
        comment = comment,
        externalRef = externalRef,
        occurredAt = occurredAt,
        recordedAt = recordedAt
    )
}

data class TransferLegPayload(
    val operationId: UUID,
    val accountId: UUID,
    val amount: BigDecimal
)