package com.github.melancholic.fintrace.core.service.command.handler.operation

import com.github.melancholic.fintrace.core.dao.EventsDAO
import com.github.melancholic.fintrace.core.domain.command.OperationCommand
import com.github.melancholic.fintrace.core.domain.event.EntityType
import com.github.melancholic.fintrace.core.domain.event.payload.EventPayload
import com.github.melancholic.fintrace.core.service.command.handler.AbstractCommandHandler

abstract class AbstractOperationCommandHandler<C : OperationCommand<R>, R, P : EventPayload>(
    eventsDAO: EventsDAO
) : AbstractCommandHandler<C, R, P>(eventsDAO), OperationCommandHandler<C, R, P> {
    override val entityType = EntityType.OPERATION
}

