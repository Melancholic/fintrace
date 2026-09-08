package com.github.melancholic.fintrace.core.service.command.handler.category

import com.github.melancholic.fintrace.core.dao.EventsDAO
import com.github.melancholic.fintrace.core.domain.command.CategoryCommand
import com.github.melancholic.fintrace.core.domain.event.EntityType
import com.github.melancholic.fintrace.core.domain.event.payload.EventPayload
import com.github.melancholic.fintrace.core.service.command.handler.AbstractCommandHandler

abstract class AbstractCategoryCommandHandler<C : CategoryCommand<R>, R, P : EventPayload>(
    val eventsDAO: EventsDAO,
) : AbstractCommandHandler<C, R, P>(eventsDAO), CategoryCommandHandler<C, R, P> {
    override val entityType = EntityType.CATEGORY
}

