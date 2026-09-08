package com.github.melancholic.fintrace.core.domain.projection

import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import com.github.melancholic.fintrace.core.domain.entity.CategorySystemCode
import java.time.LocalDateTime
import java.util.*

data class CategoryProjection(
    override val id: UUID,
    override val workspaceId: UUID,
    val parentId: UUID?,
    val name: String,
    val kind: CategoryKind,
    val archived: Boolean,
    val systemCode: CategorySystemCode?,
    val icon: String?,
    override val recordedAt: LocalDateTime
) : Projection
