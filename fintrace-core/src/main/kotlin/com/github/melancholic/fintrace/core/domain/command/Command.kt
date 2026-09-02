package com.github.melancholic.fintrace.core.domain.command

import java.time.LocalDateTime
import java.util.UUID

sealed interface Command<R> {
    val workspaceId: UUID
    val occurredAt: LocalDateTime
}

sealed interface CreateCommand<R> : Command<R>
sealed interface ReviseCommand<R> : Command<R>
sealed interface CancelCommand<R> : Command<R>