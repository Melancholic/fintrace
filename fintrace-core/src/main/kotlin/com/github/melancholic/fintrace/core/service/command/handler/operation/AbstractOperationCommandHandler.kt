package com.github.melancholic.fintrace.core.service.command.handler.operation

import com.github.melancholic.fintrace.core.dao.EventsDAO
import com.github.melancholic.fintrace.core.domain.command.CancelOperationCommand
import com.github.melancholic.fintrace.core.domain.command.CreateOperationCommand
import com.github.melancholic.fintrace.core.domain.command.OperationCommand
import com.github.melancholic.fintrace.core.domain.command.ReviseOperationCommand
import com.github.melancholic.fintrace.core.domain.event.EntityType
import com.github.melancholic.fintrace.core.domain.event.EventType
import com.github.melancholic.fintrace.core.domain.event.payload.EventPayload

abstract class AbstractOperationCommandHandler<C : OperationCommand<R>, R, P : EventPayload>(
    private val eventsDAO: EventsDAO,
) : OperationCommandHandler<C, R, P> {

    abstract fun buildEventPayload(command: C): P

    protected fun registerEvent(command: C) = eventsDAO.registerEvent(
        workspaceId = command.workspaceId,
        payload = buildEventPayload(command),
        eventType = command.eventType(),
        entityType = EntityType.OPERATION,
    )

    protected fun OperationCommand<R>.eventType(): EventType = when (this) {
        is CreateOperationCommand -> EventType.CREATED
        is ReviseOperationCommand -> EventType.REVISED
        is CancelOperationCommand -> EventType.CANCELLED
    }

}

