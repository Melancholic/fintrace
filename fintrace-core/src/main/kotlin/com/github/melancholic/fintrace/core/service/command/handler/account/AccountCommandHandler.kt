package com.github.melancholic.fintrace.core.service.command.handler.account

import com.github.melancholic.fintrace.core.domain.command.AccountCommand
import com.github.melancholic.fintrace.core.domain.event.payload.EventPayload
import com.github.melancholic.fintrace.core.service.command.handler.CommandHandler

sealed interface AccountCommandHandler<C : AccountCommand<R>, R, P : EventPayload> : CommandHandler<C, R, P>
