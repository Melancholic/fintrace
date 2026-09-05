package com.github.melancholic.fintrace.core.domain.entity

import java.time.LocalDateTime
import java.util.UUID

data class User(
    val id: UUID,
    val userName: String,
    val externalId: String,
    val createdAt: LocalDateTime
)