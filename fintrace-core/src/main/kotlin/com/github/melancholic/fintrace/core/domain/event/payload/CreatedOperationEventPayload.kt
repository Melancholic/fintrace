package com.github.melancholic.fintrace.core.domain.event.payload

import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

sealed interface CreatedOperationEventPayload : OperationEventPayload

/**
 * WARNING: Shouldn't be changed ever. 
 * In case of any changes required, have to create a next version of entity.
 */
data class CreatedOperationEventPayloadV1(
    override val id: UUID,
    val workspaceId: UUID,
    val amount: BigDecimal,
    override val occurredAt: LocalDateTime,
    override val recordedAt: LocalDateTime,
    override val version: Int = VERSION,
) : CreatedOperationEventPayload {

    override fun asProjection(): OperationProjection {
        return OperationProjection(
            id = id,
            workspaceId = workspaceId,
            amount = amount,
            occurredAt = occurredAt,
            recordedAt = recordedAt
        )
    }

    companion object {
        const val TYPE = "operation.created.v1"
        const val VERSION = 1
    }
}
