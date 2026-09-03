package com.github.melancholic.fintrace.core.domain.event

import com.github.melancholic.fintrace.core.domain.event.payload.EventPayload
import com.github.melancholic.fintrace.core.domain.event.payload.OperationCanceledV1
import com.github.melancholic.fintrace.core.domain.event.payload.OperationCreatedV1
import com.github.melancholic.fintrace.core.domain.event.payload.OperationRevisedV1
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*
import kotlin.test.assertEquals

/**
 * Uses the mapper Boot configures, since that is the one that will write `jsonb` in production —
 * a payload that round-trips through a hand-built mapper proves nothing about the real path.
 */
@JsonTest
class EventPayloadSerializationTest(@Autowired private val mapper: ObjectMapper) {

	private val payload = OperationCreatedV1(
		id = OPERATION_ID,
		workspaceId = WORKSPACE_ID,
		amount = BigDecimal("-1234.5600"),
		occurredAt = OCCURRED_AT,
		recordedAt = RECORDED_AT,
	)

	private val revised = OperationRevisedV1(
		id = OPERATION_ID,
		workspaceId = WORKSPACE_ID,
		amount = BigDecimal("-99.0000"),
		occurredAt = OCCURRED_AT,
		recordedAt = RECORDED_AT,
	)

	private val canceled = OperationCanceledV1(
		id = OPERATION_ID,
		workspaceId = WORKSPACE_ID,
		occurredAt = OCCURRED_AT,
		recordedAt = RECORDED_AT,
	)

	@Test
	fun `round-trips through the sealed interface`() {
		val json = mapper.writeValueAsString(payload)

		// Deserialised as EventPayload, not as the concrete class: this is how the event store
		// reads a row back, knowing only that the column holds some payload.
		val restored = mapper.readValue(json, EventPayload::class.java)

		assertEquals(payload, restored)
	}

	@Test
	fun `round-trips every payload kind`() {
		listOf<EventPayload>(payload, revised, canceled).forEach {
			assertEquals(it, mapper.readValue(mapper.writeValueAsString(it), EventPayload::class.java))
		}
	}

	@Test
	fun `carries a discriminator and a version`() {
		val json = mapper.readTree(mapper.writeValueAsString(payload))

		assertEquals(OperationCreatedV1.TYPE, json.get("type").asString())
		assertEquals(1, json.get("version").asInt())
	}

	@Test
	fun `gives each payload kind its own discriminator`() {
		// The three are structurally similar, so only the discriminator tells a stored row what
		// it is. A collision here would deserialise a cancellation as a revision on rebuild.
		val discriminators = listOf<EventPayload>(payload, revised, canceled)
			.map { mapper.readTree(mapper.writeValueAsString(it)).get("type").asString() }

		assertEquals(
			listOf(OperationCreatedV1.TYPE, OperationRevisedV1.TYPE, OperationCanceledV1.TYPE),
			discriminators,
		)
		assertEquals(3, discriminators.toSet().size, "discriminators must be distinct")
	}

	@Test
	fun `preserves the exact amount, scale included`() {
		val restored = mapper.readValue(
			mapper.writeValueAsString(payload),
			EventPayload::class.java,
		) as OperationCreatedV1

		// compareTo would pass on a silent scale change; equals would not. Money must survive
		// the round trip exactly, so assert the stronger property.
		assertEquals(payload.amount, restored.amount)
		assertEquals(4, restored.amount.scale())
	}

	@Test
	fun `writes timestamps without a zone`() {
		val json = mapper.readTree(mapper.writeValueAsString(payload))

		// §6.4/§6.5: LocalDateTime carries no offset, and none may be invented on the way out —
		// neither an appended zone nor a re-anchored epoch number.
		assertEquals("2026-03-15T14:30:00", json.get("occurredAt").asString())
		assertEquals("2026-03-16T09:00:00", json.get("recordedAt").asString())
	}

	private companion object {
		val OPERATION_ID: UUID = UUID.fromString("0199a1c2-3d4e-7f80-8123-456789abcdef")
		val WORKSPACE_ID: UUID = UUID.fromString("0199a1c2-3d4e-7f80-8123-000000000001")
		val OCCURRED_AT: LocalDateTime = LocalDateTime.parse("2026-03-15T14:30:00")
		val RECORDED_AT: LocalDateTime = LocalDateTime.parse("2026-03-16T09:00:00")
	}
}
