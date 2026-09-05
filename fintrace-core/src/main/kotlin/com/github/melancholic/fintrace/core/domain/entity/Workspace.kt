package com.github.melancholic.fintrace.core.domain.entity

import java.time.LocalDateTime
import java.util.*

data class Workspace(
    val id: UUID,
    val name: String,
    val status: WorkspaceStatus,
    val ownerId: UUID,
    val defaultCurrency: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val version: Long,
    val deletedAt: LocalDateTime?
)

enum class WorkspaceStatus {
    NEW, ACTIVE, ARCHIVED, DELETED
}