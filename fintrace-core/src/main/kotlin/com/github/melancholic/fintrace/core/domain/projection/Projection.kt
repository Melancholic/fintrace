package com.github.melancholic.fintrace.core.domain.projection

import java.time.LocalDateTime
import java.util.*

sealed interface Projection {
    val id: UUID
    val workspaceId: UUID
    val recordedAt: LocalDateTime
}

sealed interface TemporalProjection : Projection {
    val occurredAt: LocalDateTime
}
