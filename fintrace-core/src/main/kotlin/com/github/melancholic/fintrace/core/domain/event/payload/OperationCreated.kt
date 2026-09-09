package com.github.melancholic.fintrace.core.domain.event.payload

import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

sealed interface OperationCreated : BalanceOperationEventPayload {

}

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
    override val accountId: UUID,
    override val kind: OperationKind,
    override val categoryId: UUID,
    override val transferId: UUID?,
    override val counterpartId: UUID?,
    override val externalRef: String?,
    override val comment: String?,
    override val version: Int = VERSION
) : OperationCreated {

    companion object {
        const val TYPE = "operation.created.v1"
        const val VERSION = 1
    }
}
