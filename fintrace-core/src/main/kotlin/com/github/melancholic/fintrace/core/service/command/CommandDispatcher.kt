package com.github.melancholic.fintrace.core.service.command

import com.github.melancholic.fintrace.core.domain.command.Command
import com.github.melancholic.fintrace.core.service.command.handler.CommandHandler
import org.springframework.stereotype.Service
import kotlin.reflect.KClass

sealed interface CommandDispatcher {
    fun <R> dispatch(command: Command<R>): R
}

@Service
class CommandDispatcherImpl(
    handlers: List<CommandHandler<*, *>>
) : CommandDispatcher {
    private val handlers: Map<KClass<*>, CommandHandler<*, *>> = handlers.associateBy { it.commandType }

    override fun <R> dispatch(command: Command<R>): R {
        return resolveHandler(command).handle(command)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <C : Command<R>, R> resolveHandler(command: Command<R>): CommandHandler<C, R> {
        return handlers[command::class] as? CommandHandler<C, R>
            ?: throw IllegalArgumentException("Unknown command type: ${command::class.qualifiedName}")
    }
}