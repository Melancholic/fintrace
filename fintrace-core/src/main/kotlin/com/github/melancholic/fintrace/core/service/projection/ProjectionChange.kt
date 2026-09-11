package com.github.melancholic.fintrace.core.service.projection

import com.github.melancholic.fintrace.core.domain.projection.Projection
import java.util.*

sealed interface ProjectionChange {
    data class Upsert(val rows: List<Projection>) : ProjectionChange
    data class Remove(val target: ProjectionTarget, val workspaceId: UUID, val ids: Set<UUID>) : ProjectionChange
}

enum class ProjectionTarget { OPERATION, ACCOUNT, CATEGORY, BALANCE_ANCHOR }