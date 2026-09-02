package com.github.melancholic.fintrace.core.domain.event

import com.github.melancholic.fintrace.core.domain.event.payload.EventPayload
import com.github.melancholic.fintrace.core.domain.event.payload.CreatedOperationEventPayloadV1
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Uses the mapper Boot configures, since that is the one that will write `jsonb` in production —
 * a payload that round-trips through a hand-built mapper proves nothing about the real path.
 */
@JsonTest
class EventPayloadSerializationTest(@Autowired private val mapper: ObjectMapper) {

	private val payload = CreatedOperationEventPayloadV1(
		id = UUID.fromString("0199a1c2-3d4e-7f80-8123-456789abcdef"),
		workspaceId = UUID.fromString("0199a1c2-3d4e-7f80-8123-000000000001"),
		amount = BigDecimal("-1234.5600"),
		occurredAt = LocalDateTime.parse("2026-03-15T14:30:00"),
		recordedAt = LocalDateTime.parse("2026-03-16T09:00:00"),
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
	fun `carries a discriminator and a version`() {
		val json = mapper.readTree(mapper.writeValueAsString(payload))

		assertEquals(CreatedOperationEventPayloadV1.TYPE, json.get("type").asString())
		assertEquals(1, json.get("version").asInt())
	}

	@Test
	fun `preserves the exact amount, scale included`() {
		val restored = mapper.readValue(
			mapper.writeValueAsString(payload),
			EventPayload::class.java,
		) as CreatedOperationEventPayloadV1

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
}
