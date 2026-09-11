package com.github.melancholic.fintrace.core.service.command.handler.transfer

import com.github.melancholic.fintrace.core.dao.EventsDAO
import com.github.melancholic.fintrace.core.domain.command.TransferCommand
import com.github.melancholic.fintrace.core.domain.event.EntityType
import com.github.melancholic.fintrace.core.domain.event.payload.EventPayload
import com.github.melancholic.fintrace.core.service.command.handler.AbstractCommandHandler

abstract class AbstractTransferCommandHandler<C : TransferCommand<R>, R, P : EventPayload>(
    val eventsDAO: EventsDAO,
) : AbstractCommandHandler<C, R, P>(eventsDAO), TransferCommandHandler<C, R, P> {
    override val entityType = EntityType.TRANSFER
}