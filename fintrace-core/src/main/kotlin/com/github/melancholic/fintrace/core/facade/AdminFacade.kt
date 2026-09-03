package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.dao.EventsDAO
import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.projection.DeleteOperationProjection
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

interface AdminFacade {
    fun replayWorkspace(workspaceId: UUID)
}

@Service
class AdminFacadeImpl(
    private val eventsDAO: EventsDAO,
    private val operationProjectionDAO: OperationProjectionDAO
) : AdminFacade {

    @Transactional
    override fun replayWorkspace(workspaceId: UUID) {
        operationProjectionDAO.removeAll(workspaceId)
        val events = eventsDAO.loadAll(workspaceId)
        events.map { it.payload.asProjection() }
            .forEach {
                when (it) {
                    is OperationProjection -> operationProjectionDAO.createOrUpdate(it)
                    is DeleteOperationProjection -> operationProjectionDAO.remove(it.workspaceId, it.id)
                }
            }
    }

}