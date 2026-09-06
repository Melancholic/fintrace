package com.github.melancholic.fintrace.core.service.command.handler.account

import com.github.melancholic.fintrace.core.dao.EventsDAO
import com.github.melancholic.fintrace.core.domain.command.AccountCommand
import com.github.melancholic.fintrace.core.domain.event.EntityType
import com.github.melancholic.fintrace.core.domain.event.payload.EventPayload
import com.github.melancholic.fintrace.core.service.command.handler.AbstractCommandHandler

abstract class AbstractAccountCommandHandler<C : AccountCommand<R>, R, P : EventPayload>(
    val eventsDAO: EventsDAO,
) : AbstractCommandHandler<C, R, P>(eventsDAO), AccountCommandHandler<C, R, P> {
    override val entityType = EntityType.ACCOUNT
}

