package com.github.melancholic.fintrace.core.service.command.handler.balanceanchor

import com.github.melancholic.fintrace.core.dao.EventsDAO
import com.github.melancholic.fintrace.core.domain.command.BalanceAnchorCommand
import com.github.melancholic.fintrace.core.domain.event.EntityType
import com.github.melancholic.fintrace.core.domain.event.payload.EventPayload
import com.github.melancholic.fintrace.core.service.command.handler.AbstractCommandHandler

abstract class AbstractBalanceAnchorCommandHandler<C : BalanceAnchorCommand<R>, R, P : EventPayload>(
    val eventsDAO: EventsDAO,
) : AbstractCommandHandler<C, R, P>(eventsDAO), BalanceAnchorCommandHandler<C, R, P> {
    override val entityType = EntityType.BALANCE_ANCHOR
}