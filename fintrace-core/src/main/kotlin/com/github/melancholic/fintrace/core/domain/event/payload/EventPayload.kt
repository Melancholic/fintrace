package com.github.melancholic.fintrace.core.domain.event.payload

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.domain.projection.Projection
import java.time.LocalDateTime
import java.util.UUID

/**
 * The body of an event, stored as `jsonb`.
 *
 * Payloads carry the **complete resulting state** of the entity, never a diff (§4.4), so
 * applying one is "take this as the new version" rather than a merge. Deliberately not
 * copying MoneyOK's delta encoding, which is what makes the current bot hard to get right.
 *
 * Every payload is versioned from day one (§4.10): an event written today will be read in
 * two years, and the shape it was written in has to stay recoverable.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
	JsonSubTypes.Type(value = CreatedOperationEventPayloadV1::class, name = CreatedOperationEventPayloadV1.TYPE),
)
sealed interface EventPayload {
	val version: Int
	val id: UUID
	val occurredAt: LocalDateTime
	val recordedAt: LocalDateTime
    fun asProjection(): Projection
}

