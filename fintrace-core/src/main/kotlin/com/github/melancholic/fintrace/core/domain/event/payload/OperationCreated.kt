package com.github.melancholic.fintrace.core.domain.event.payload

import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.service.projection.ProjectionChange
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

sealed interface OperationCreated : BalanceOperationEventPayload {
    override fun projectionChange(): ProjectionChange = ProjectionChange.Upsert(
        listOf(
            OperationProjection(
                id = id,
                workspaceId = workspaceId,
                amount = amount,
                occurredAt = occurredAt,
                recordedAt = recordedAt,
            )
        )
    )
}

/**
 * WARNING: Shouldn't be changed ever. 
 * In case of any changes required, have to create a next version of entity.
 */
data class OperationCreatedV1(
    override val id: UUID,
    override val workspaceId: UUID,
    override val amount: BigDecimal,
    override val occurredAt: LocalDateTime,
    override val recordedAt: LocalDateTime,
    override val version: Int = VERSION,
) : OperationCreated {

    companion object {
        const val TYPE = "operation.created.v1"
        const val VERSION = 1
    }
}
