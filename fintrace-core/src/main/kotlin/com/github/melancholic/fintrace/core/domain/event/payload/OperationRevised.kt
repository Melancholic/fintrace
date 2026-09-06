package com.github.melancholic.fintrace.core.domain.event.payload

import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.service.projection.ProjectionChange
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

sealed interface OperationRevised : BalanceOperationEventPayload {
    override fun projectionChange(): ProjectionChange = ProjectionChange
        .Upsert(listOf(projection()))

    fun projection(): OperationProjection = OperationProjection(
        id = id,
        workspaceId = workspaceId,
        amount = amount,
        occurredAt = occurredAt,
        recordedAt = recordedAt,
    )

}

/**
 * WARNING: Shouldn't be changed ever. 
 * In case of any changes required, have to create a next version of entity.
 */
data class OperationRevisedV1(
    override val version: Int = VERSION,
    override val amount: BigDecimal,
    override val id: UUID,
    override val workspaceId: UUID,
    override val occurredAt: LocalDateTime,
    override val recordedAt: LocalDateTime,
) : OperationRevised {
    companion object {
        const val TYPE = "operation.revised.v1"
        const val VERSION = 1
    }
}
