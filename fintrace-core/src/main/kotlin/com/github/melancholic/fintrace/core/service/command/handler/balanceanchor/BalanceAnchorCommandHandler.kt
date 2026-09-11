package com.github.melancholic.fintrace.core.service.command.handler.balanceanchor

import com.github.melancholic.fintrace.core.domain.command.BalanceAnchorCommand
import com.github.melancholic.fintrace.core.domain.event.payload.EventPayload
import com.github.melancholic.fintrace.core.service.command.handler.CommandHandler

sealed interface BalanceAnchorCommandHandler<C : BalanceAnchorCommand<R>, R, P : EventPayload> : CommandHandler<C, R, P>
