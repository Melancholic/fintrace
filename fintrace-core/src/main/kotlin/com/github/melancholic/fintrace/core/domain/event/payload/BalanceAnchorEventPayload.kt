package com.github.melancholic.fintrace.core.domain.event.payload

import java.util.*

sealed interface BalanceAnchorEventPayload : EventPayload {
    val accountId: UUID
}