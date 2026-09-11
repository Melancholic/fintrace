package com.github.melancholic.fintrace.core.domain.event.payload

import com.github.melancholic.fintrace.core.service.projection.ProjectionChange
import com.github.melancholic.fintrace.core.service.projection.ProjectionTarget
import java.time.LocalDateTime
import java.util.*

sealed interface BalanceAnchorCanceled : BalanceAnchorEventPayload {
    override fun projectionChange() = ProjectionChange.Remove(
        ProjectionTarget.BALANCE_ANCHOR,
        workspaceId = workspaceId,
        setOf(id)
    )
}

/**
 * WARNING: Shouldn't be changed ever. 
 * In case of any changes required, have to create a next version of entity.
 */
data class BalanceAnchorCanceledV1(
    override val id: UUID,
    override val workspaceId: UUID,
    override val accountId: UUID,
    override val recordedAt: LocalDateTime,
    override val version: Int = VERSION
) : BalanceAnchorCanceled {

    companion object {
        const val TYPE = "balance_anchor.canceled.v1"
        const val VERSION = 1
    }
}
