package com.github.melancholic.fintrace.core.domain.command

import com.github.melancholic.fintrace.core.domain.event.EventType
import java.time.LocalDateTime
import java.util.*

sealed interface Command<R> {
    val workspaceId: UUID
    fun eventType(): EventType
}

sealed interface TemporalCommand<R> : Command<R> {
    val occurredAt: LocalDateTime
}

sealed interface CreateCommand<R> : Command<R> {
    override fun eventType() = EventType.CREATED
}

sealed interface ReviseCommand<R> : Command<R> {
    override fun eventType() = EventType.REVISED
}

sealed interface CancelCommand<R> : Command<R> {
    override fun eventType() = EventType.CANCELLED
}