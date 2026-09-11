package com.github.melancholic.fintrace.core.domain.event.payload

import java.time.LocalDateTime
import java.util.*

sealed interface TransferRevised : TransferStateEventPayload

/**
 * WARNING: Shouldn't be changed ever.
 * In case of any changes required, have to create a next version of entity.
 */
data class TransferRevisedV1(
    override val id: UUID,
    override val workspaceId: UUID,
    override val occurredAt: LocalDateTime,
    override val recordedAt: LocalDateTime,
    override val source: TransferLegPayload,
    override val target: TransferLegPayload,
    override val comment: String?,
    override val externalRef: String?,

    override val version: Int = VERSION
) : TransferRevised {

    companion object {
        const val TYPE = "transfer.revised.v1"
        const val VERSION = 1
    }
}