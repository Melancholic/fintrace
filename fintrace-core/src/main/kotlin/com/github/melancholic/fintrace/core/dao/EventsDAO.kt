package com.github.melancholic.fintrace.core.dao

import com.github.melancholic.fintrace.core.dao.mapper.EventRowMapper
import com.github.melancholic.fintrace.core.domain.event.EntityType
import com.github.melancholic.fintrace.core.domain.event.Event
import com.github.melancholic.fintrace.core.domain.event.EventType
import com.github.melancholic.fintrace.core.domain.event.payload.EventPayload
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.util.*

interface EventsDAO {
    fun registerEvent(
        workspaceId: UUID,
        entityType: EntityType,
        eventType: EventType,
        payload: EventPayload
    ): Event

    fun loadAll(workspaceId: UUID): List<Event>
}

@Suppress("UNCHECKED_CAST")
@Repository
class EventsDAOImpl(
    private val jdbc: JdbcClient,
    private val mapper: ObjectMapper,
    private val eventRowMapper: EventRowMapper
) : EventsDAO {

    override fun registerEvent(
        workspaceId: UUID,
        entityType: EntityType,
        eventType: EventType,
        payload: EventPayload
    ): Event {
        val id = jdbc.sql(INSERT)
            .param("workspaceId", workspaceId)
            .param("aggregateType", entityType.name)
            .param("aggregateId", payload.id)
            .param("eventType", eventType.name)
            .param("payload", mapper.writeValueAsString(payload))
            .param("occurredAt", payload.occurredAt)
            .param("recordedAt", payload.recordedAt)
            .query(Long::class.java)
            .single()

        return Event(
            id = id,
            workspaceId = workspaceId,
            entityType = entityType,
            entityId = payload.id,
            eventType = eventType,
            payload = payload,
            occurredAt = payload.occurredAt,
            recordedAt = payload.recordedAt,
        )
    }

    override fun loadAll(workspaceId: UUID): List<Event> = jdbc.sql(SELECT_BY_WORKSPACE)
        .param("workspaceId", workspaceId)
        .query(eventRowMapper)
        .list()

    private companion object {
        const val INSERT = """
            INSERT INTO t_events (workspace_id, aggregate_type, aggregate_id, event_type,
                                payload, occurred_at, recorded_at)
            VALUES (:workspaceId, :aggregateType, :aggregateId, :eventType,
                    CAST(:payload AS jsonb), :occurredAt, :recordedAt)
            RETURNING id
        """

        const val SELECT_BY_WORKSPACE = """
            SELECT id, workspace_id, aggregate_type, aggregate_id, 
                   event_type, payload, occurred_at, recorded_at 
            FROM t_events
            WHERE workspace_id=:workspaceId
            ORDER BY id ASC
        """
    }
}