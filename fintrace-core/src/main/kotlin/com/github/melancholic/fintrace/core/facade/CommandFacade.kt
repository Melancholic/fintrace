package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.domain.command.Command
import com.github.melancholic.fintrace.core.service.command.CommandDispatcher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

interface CommandFacade {
    fun <R> processCommand(command: Command<R>): R
}

@Service
class CommandFacadeImpl(
    val commandDispatcher: CommandDispatcher,
) : CommandFacade {

    @Transactional
    override fun <R> processCommand(command: Command<R>): R {
        return commandDispatcher.dispatch(command)
    }

}