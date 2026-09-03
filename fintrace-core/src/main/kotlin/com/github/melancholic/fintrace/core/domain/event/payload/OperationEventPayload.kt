package com.github.melancholic.fintrace.core.domain.event.payload

import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import java.math.BigDecimal

sealed interface OperationEventPayload : EventPayload

sealed interface BalanceOperationEventPayload : OperationEventPayload {
    val amount: BigDecimal

    override fun asProjection(): OperationProjection {
        return OperationProjection(
            id = id,
            workspaceId = workspaceId,
            amount = amount,
            occurredAt = occurredAt,
            recordedAt = recordedAt
        )
    }
}