package com.github.melancholic.fintrace.core.domain.event

import com.github.melancholic.fintrace.core.domain.event.payload.EventPayload
import java.time.LocalDateTime
import java.util.*

data class Event(
	val id: Long,
	val workspaceId: UUID,
	val entityType: EntityType,
	val entityId: UUID,
	val eventType: EventType,
	val payload: EventPayload,
	val occurredAt: LocalDateTime,
	val recordedAt: LocalDateTime,
)

enum class EntityType {
	OPERATION,
	ACCOUNT,
	CATEGORY,
	TRANSFER,
	BALANCE_ANCHOR
}

enum class EventType {
	CREATED,
	REVISED,
	CANCELLED,
}
