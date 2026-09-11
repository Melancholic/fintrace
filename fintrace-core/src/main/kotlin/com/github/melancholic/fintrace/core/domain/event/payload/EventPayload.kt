package com.github.melancholic.fintrace.core.domain.event.payload

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.github.melancholic.fintrace.core.service.projection.ProjectionChange
import java.time.LocalDateTime
import java.util.*

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
	JsonSubTypes.Type(value = OperationCreatedV1::class, name = OperationCreatedV1.TYPE),
	JsonSubTypes.Type(value = OperationRevisedV1::class, name = OperationRevisedV1.TYPE),
	JsonSubTypes.Type(value = OperationCanceledV1::class, name = OperationCanceledV1.TYPE),

	JsonSubTypes.Type(value = AccountCreatedV1::class, name = AccountCreatedV1.TYPE),
	JsonSubTypes.Type(value = AccountRevisedV1::class, name = AccountRevisedV1.TYPE),

	JsonSubTypes.Type(value = CategoryCreatedV1::class, name = CategoryCreatedV1.TYPE),
	JsonSubTypes.Type(value = CategoryRevisedV1::class, name = CategoryRevisedV1.TYPE),

	JsonSubTypes.Type(value = TransferCreatedV1::class, name = TransferCreatedV1.TYPE),
	JsonSubTypes.Type(value = TransferRevisedV1::class, name = TransferRevisedV1.TYPE),
	JsonSubTypes.Type(value = TransferCanceledV1::class, name = TransferCanceledV1.TYPE)
)
sealed interface EventPayload {
	val version: Int
	val id: UUID
	val workspaceId: UUID
	val recordedAt: LocalDateTime
	fun projectionChange(): ProjectionChange
}

sealed interface TemporalEventPayload : EventPayload {
	val occurredAt: LocalDateTime
}