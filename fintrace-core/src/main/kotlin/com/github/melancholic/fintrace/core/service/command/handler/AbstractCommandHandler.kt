package com.github.melancholic.fintrace.core.service.command.handler

import com.github.melancholic.fintrace.core.dao.EventsDAO
import com.github.melancholic.fintrace.core.domain.command.Command
import com.github.melancholic.fintrace.core.domain.event.EntityType
import com.github.melancholic.fintrace.core.domain.event.payload.EventPayload
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import java.util.*

abstract class AbstractCommandHandler<C : Command<R>, R, P : EventPayload>(
    private val eventsDAO: EventsDAO,
) : CommandHandler<C, R, P> {

    abstract val entityType: EntityType

    protected fun registerEvent(command: C, payload: P) = eventsDAO.registerEvent(
        workspaceId = command.workspaceId,
        payload = payload,
        eventType = command.eventType(),
        entityType = entityType
    )

    protected fun currentPayload(workspaceId: UUID, aggregateId: UUID): EventPayload =
        eventsDAO.latestPayload(workspaceId, aggregateId)
            ?: throw NotFoundEntityException("No events for entity (workspaceId='$workspaceId', id='$aggregateId')")
}