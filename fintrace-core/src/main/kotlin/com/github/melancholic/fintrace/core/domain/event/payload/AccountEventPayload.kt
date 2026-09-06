package com.github.melancholic.fintrace.core.domain.event.payload

import com.github.melancholic.fintrace.core.domain.projection.AccountProjection
import com.github.melancholic.fintrace.core.service.projection.ProjectionChange

sealed interface AccountEventPayload : EventPayload {
    val name: String
    val currency: String
    val archived: Boolean
    val icon: String?

    override fun projectionChange(): ProjectionChange = ProjectionChange
        .Upsert(listOf(projection()))

    fun projection(): AccountProjection = AccountProjection(
        id = id,
        workspaceId = workspaceId,
        name = name,
        currency = currency,
        archived = archived,
        icon = icon,
        recordedAt = recordedAt
    )
}
