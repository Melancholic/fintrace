package com.github.melancholic.fintrace.core.domain.event.payload

import java.time.LocalDateTime
import java.util.*

sealed interface AccountCreated : AccountEventPayload

/**
 * WARNING: Shouldn't be changed ever. 
 * In case of any changes required, have to create a next version of entity.
 */
data class AccountCreatedV1(
    override val id: UUID,
    override val workspaceId: UUID,
    override val name: String,
    override val currency: String,
    override val archived: Boolean,
    override val icon: String?,
    override val recordedAt: LocalDateTime,
    override val version: Int = VERSION,
) : AccountCreated {

    companion object {
        const val TYPE = "account.created.v1"
        const val VERSION = 1
    }
}