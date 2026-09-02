package com.github.melancholic.fintrace.core.domain.event.payload

import com.github.melancholic.fintrace.core.domain.projection.OperationProjection

sealed interface OperationEventPayload : EventPayload {
    override fun asProjection(): OperationProjection
}