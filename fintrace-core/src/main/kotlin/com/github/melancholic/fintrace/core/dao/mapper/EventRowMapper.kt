package com.github.melancholic.fintrace.core.dao.mapper

import com.github.melancholic.fintrace.core.domain.event.EntityType
import com.github.melancholic.fintrace.core.domain.event.Event
import com.github.melancholic.fintrace.core.domain.event.EventType
import com.github.melancholic.fintrace.core.domain.event.payload.EventPayload
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.time.LocalDateTime
import java.util.UUID

@Component
class EventRowMapper(
    private val mapper: JsonMapper
) : RowMapper<Event> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int
    ): Event = Event(
        id = rs.getLong("id"),
        workspaceId = rs.getObject("workspace_id", UUID::class.java),
        entityType = EntityType.valueOf(rs.getString("aggregate_type")),
        entityId = rs.getObject("aggregate_id", UUID::class.java),
        eventType = EventType.valueOf(rs.getString("event_type")),
        payload = mapper.readValue(rs.getString("payload"), EventPayload::class.java),
        occurredAt = rs.getObject("occurred_at", LocalDateTime::class.java),
        recordedAt = rs.getObject("recorded_at", LocalDateTime::class.java),
    )
}