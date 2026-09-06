package com.github.melancholic.fintrace.core.domain.command

import java.time.LocalDateTime
import java.util.*

sealed interface Command<R> {
    val workspaceId: UUID
}

sealed interface TemporalCommand<R> : Command<R> {
    val occurredAt: LocalDateTime
}

sealed interface CreateCommand<R> : TemporalCommand<R>
sealed interface ReviseCommand<R> : TemporalCommand<R>
sealed interface CancelCommand<R> : Command<R>