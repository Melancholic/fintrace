package com.github.melancholic.fintrace.core.domain.event.payload

import com.github.melancholic.fintrace.core.service.projection.ProjectionChange
import com.github.melancholic.fintrace.core.service.projection.ProjectionTarget
import java.time.LocalDateTime
import java.util.*

sealed interface OperationCanceled : OperationEventPayload {
    override fun projectionChange() = ProjectionChange.Remove(
        ProjectionTarget.OPERATION,
        workspaceId,
        setOf(id)
    )
}

/**
 * WARNING: Shouldn't be changed ever. 
 * In case of any changes required, have to create a next version of entity.
 */
data class OperationCanceledV1(
    override val id: UUID,
    override val workspaceId: UUID,
    override val version: Int = VERSION,
    override val recordedAt: LocalDateTime,
) : OperationCanceled {

    companion object {
        const val TYPE = "operation.canceled.v1"
        const val VERSION = 1
    }
}
