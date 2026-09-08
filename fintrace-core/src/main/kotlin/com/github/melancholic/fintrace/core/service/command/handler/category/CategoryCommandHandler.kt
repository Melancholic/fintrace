package com.github.melancholic.fintrace.core.service.command.handler.category

import com.github.melancholic.fintrace.core.domain.command.CategoryCommand
import com.github.melancholic.fintrace.core.domain.event.payload.EventPayload
import com.github.melancholic.fintrace.core.service.command.handler.CommandHandler

sealed interface CategoryCommandHandler<C : CategoryCommand<R>, R, P : EventPayload> : CommandHandler<C, R, P>
