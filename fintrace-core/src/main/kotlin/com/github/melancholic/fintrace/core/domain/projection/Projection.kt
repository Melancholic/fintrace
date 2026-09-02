package com.github.melancholic.fintrace.core.domain.projection

import java.time.LocalDateTime
import java.util.*

sealed interface Projection {
    val id: UUID
    val workspaceId: UUID
    val occurredAt: LocalDateTime
    val recordedAt: LocalDateTime
}