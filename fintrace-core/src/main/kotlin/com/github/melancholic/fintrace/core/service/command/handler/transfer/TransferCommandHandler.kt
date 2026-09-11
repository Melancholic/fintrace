package com.github.melancholic.fintrace.core.service.command.handler.transfer

import com.github.melancholic.fintrace.core.domain.command.TransferCommand
import com.github.melancholic.fintrace.core.domain.event.payload.EventPayload
import com.github.melancholic.fintrace.core.service.command.handler.CommandHandler

sealed interface TransferCommandHandler<C : TransferCommand<R>, R, P : EventPayload> : CommandHandler<C, R, P>
