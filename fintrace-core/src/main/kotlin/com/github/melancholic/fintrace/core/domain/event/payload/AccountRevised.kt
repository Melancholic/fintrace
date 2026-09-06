package com.github.melancholic.fintrace.core.domain.event.payload

import java.time.LocalDateTime
import java.util.*

sealed interface AccountRevised : AccountEventPayload

/**
 * WARNING: Shouldn't be changed ever.
 * In case of any changes required, have to create a next version of entity.
 */
data class AccountRevisedV1(
    override val id: UUID,
    override val workspaceId: UUID,
    override val name: String,
    override val currency: String,
    override val archived: Boolean,
    override val icon: String?,
    override val recordedAt: LocalDateTime,
    override val version: Int = VERSION,
) : AccountRevised {

    companion object {
        const val TYPE = "account.revised.v1"
        const val VERSION = 1
    }
}