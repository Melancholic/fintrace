package com.github.melancholic.fintrace.core.domain.event.payload

import java.math.BigDecimal

sealed interface OperationEventPayload : EventPayload

sealed interface BalanceOperationEventPayload : OperationEventPayload, TemporalEventPayload {
    val amount: BigDecimal
}