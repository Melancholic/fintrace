package com.github.melancholic.fintrace.core.dao.projection

import com.github.melancholic.fintrace.core.domain.projection.Projection
import com.github.melancholic.fintrace.core.service.projection.ProjectionTarget
import java.util.*

sealed interface ProjectionDAO<P : Projection> {
    fun supportedClass(): Class<P>
    fun projectionTarget(): ProjectionTarget

    fun createOrUpdate(projection: P): UUID
    fun removeAll(workspaceId: UUID)
    fun remove(workspaceId: UUID, ids: Set<UUID>)
}