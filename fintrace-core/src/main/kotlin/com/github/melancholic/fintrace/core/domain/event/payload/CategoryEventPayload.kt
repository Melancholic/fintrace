package com.github.melancholic.fintrace.core.domain.event.payload

import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import com.github.melancholic.fintrace.core.domain.entity.CategorySystemCode
import com.github.melancholic.fintrace.core.domain.projection.CategoryProjection
import com.github.melancholic.fintrace.core.service.projection.ProjectionChange
import java.util.*

sealed interface CategoryEventPayload : EventPayload {
    val parentId: UUID?
    val name: String
    val kind: CategoryKind
    val icon: String?
    val archived: Boolean
    val systemCode: CategorySystemCode?

    override fun projectionChange(): ProjectionChange = ProjectionChange
        .Upsert(listOf(projection()))

    fun projection(): CategoryProjection = CategoryProjection(
        id = id,
        workspaceId = workspaceId,
        parentId = parentId,
        name = name,
        kind = kind,
        icon = icon,
        archived = archived,
        systemCode = systemCode,
        recordedAt = recordedAt
    )
}
