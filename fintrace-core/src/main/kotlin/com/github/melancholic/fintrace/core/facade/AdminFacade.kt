package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.dao.EventsDAO
import com.github.melancholic.fintrace.core.service.projection.ProjectionApplier
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

interface AdminFacade {
    fun replayWorkspace(workspaceId: UUID)
}

@Service
class AdminFacadeImpl(
    private val eventsDAO: EventsDAO,
    private val projectionApplier: ProjectionApplier
) : AdminFacade {

    @Transactional
    override fun replayWorkspace(workspaceId: UUID) {
        projectionApplier.clear(workspaceId)
        eventsDAO.loadAll(workspaceId).forEach { projectionApplier.apply(it.payload.projectionChange()) }
    }

}