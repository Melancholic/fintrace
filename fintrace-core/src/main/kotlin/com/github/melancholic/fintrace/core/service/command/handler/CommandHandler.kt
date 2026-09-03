package com.github.melancholic.fintrace.core.service.command.handler

import com.github.melancholic.fintrace.core.domain.command.Command
import com.github.melancholic.fintrace.core.domain.event.payload.EventPayload
import kotlin.reflect.KClass

interface CommandHandler<C : Command<R>, R, P : EventPayload> {
    val commandType: KClass<out C>
    fun handle(command: C): R
}