package com.github.melancholic.fintrace.core.service.projection

import com.github.melancholic.fintrace.core.dao.projection.ProjectionDAORegistry
import com.github.melancholic.fintrace.core.domain.projection.Projection
import org.springframework.stereotype.Service
import java.util.*

interface ProjectionApplier {
    fun apply(change: ProjectionChange)
    fun clear(workspaceId: UUID)
}

@Service
class ProjectionApplierImpl(
    private val projectionDAORegistry: ProjectionDAORegistry
) : ProjectionApplier {

    override fun apply(change: ProjectionChange) {
        when (change) {
            is ProjectionChange.Upsert -> change.rows.forEach { upsert(it) }
            is ProjectionChange.Remove -> remove(change)
        }
    }

    override fun clear(workspaceId: UUID) {
        projectionDAORegistry.asList().forEach { it.removeAll(workspaceId) }
    }

    private fun <P : Projection> upsert(row: P) {
        projectionDAORegistry.resolve(row.javaClass).createOrUpdate(row)
    }

    private fun remove(change: ProjectionChange.Remove) {
        projectionDAORegistry.resolve(change.target).remove(change.workspaceId, change.ids)
    }
}