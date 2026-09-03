package com.github.melancholic.fintrace.core.domain.event.payload

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

sealed interface OperationCreated : BalanceOperationEventPayload

/**
 * WARNING: Shouldn't be changed ever. 
 * In case of any changes required, have to create a next version of entity.
 */
data class OperationCreatedV1(
    override val id: UUID,
    override val workspaceId: UUID,
    override val amount: BigDecimal,
    override val occurredAt: LocalDateTime,
    override val recordedAt: LocalDateTime,
    override val version: Int = VERSION,
) : OperationCreated {

    companion object {
        const val TYPE = "operation.created.v1"
        const val VERSION = 1
    }
}
