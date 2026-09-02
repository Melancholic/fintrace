package com.github.melancholic.fintrace.core.service.command.handler.operation

import com.github.melancholic.fintrace.core.domain.command.OperationCommand
import com.github.melancholic.fintrace.core.service.command.handler.CommandHandler

interface OperationCommandHandler<C : OperationCommand<R>, R> : CommandHandler<C, R>
