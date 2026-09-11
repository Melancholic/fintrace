package com.github.melancholic.fintrace.core.domain.event.payload

import com.github.melancholic.fintrace.core.service.projection.ProjectionChange
import com.github.melancholic.fintrace.core.service.projection.ProjectionTarget
import java.time.LocalDateTime
import java.util.*

sealed interface TransferCanceled : TransferEventPayload {
    val legIds: Set<UUID>

    override fun projectionChange() = ProjectionChange.Remove(
        ProjectionTarget.OPERATION,
        workspaceId,
        legIds
    )
}

/**
 * WARNING: Shouldn't be changed ever.
 * In case of any changes required, have to create a next version of entity.
 */
data class TransferCanceledV1(
    override val id: UUID,
    override val workspaceId: UUID,
    override val legIds: Set<UUID>,
    override val recordedAt: LocalDateTime,
    override val version: Int = VERSION
) : TransferCanceled {

    companion object {
        const val TYPE = "transfer.canceled.v1"
        const val VERSION = 1
    }
}