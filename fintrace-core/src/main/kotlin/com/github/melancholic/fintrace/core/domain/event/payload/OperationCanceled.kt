package com.github.melancholic.fintrace.core.domain.event.payload

import com.github.melancholic.fintrace.core.domain.projection.DeleteOperationProjection
import java.time.LocalDateTime
import java.util.*

sealed interface OperationCanceled : OperationEventPayload {
    override fun asProjection(): DeleteOperationProjection
}

/**
 * WARNING: Shouldn't be changed ever. 
 * In case of any changes required, have to create a next version of entity.
 */
data class OperationCanceledV1(
    override val id: UUID,
    override val workspaceId: UUID,
    override val version: Int = VERSION,
    override val occurredAt: LocalDateTime,
    override val recordedAt: LocalDateTime,
) : OperationCanceled {

    override fun asProjection(): DeleteOperationProjection {
        return DeleteOperationProjection(
            id = id,
            workspaceId = workspaceId,
            occurredAt = occurredAt,
            recordedAt = recordedAt
        )
    }

    companion object {
        const val TYPE = "operation.canceled.v1"
        const val VERSION = 1
    }
}
