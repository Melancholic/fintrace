package com.github.melancholic.fintrace.core.domain.projection

import java.time.LocalDateTime
import java.util.*

data class AccountProjection(
    override val id: UUID,
    override val workspaceId: UUID,
    val name: String,
    val currency: String,
    val archived: Boolean,
    val icon: String?,
    override val recordedAt: LocalDateTime
) : Projection