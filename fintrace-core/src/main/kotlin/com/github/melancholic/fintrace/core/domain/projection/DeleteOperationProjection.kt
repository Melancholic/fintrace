package com.github.melancholic.fintrace.core.domain.projection

import java.time.LocalDateTime
import java.util.*

data class DeleteOperationProjection(
    override val id: UUID,
    override val workspaceId: UUID,
    override val occurredAt: LocalDateTime,
    override val recordedAt: LocalDateTime,
) : Projection