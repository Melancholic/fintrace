package com.github.melancholic.fintrace.core.service.command.handler

import com.github.melancholic.fintrace.core.domain.command.Command
import kotlin.reflect.KClass

interface CommandHandler<C : Command<R>, R> {
    val commandType: KClass<out C>
    fun handle(command: C): R
}