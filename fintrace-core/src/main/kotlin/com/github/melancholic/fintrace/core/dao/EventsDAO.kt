package com.github.melancholic.fintrace.core.dao

import com.github.melancholic.fintrace.core.dao.mapper.EventRowMapper
import com.github.melancholic.fintrace.core.domain.event.EntityType
import com.github.melancholic.fintrace.core.domain.event.Event
import com.github.melancholic.fintrace.core.domain.event.EventType
import com.github.melancholic.fintrace.core.domain.event.payload.EventPayload
import com.github.melancholic.fintrace.core.domain.event.payload.TemporalEventPayload
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

    fun latestPayload(workspaceId: UUID, aggregateId: UUID): EventPayload?
}

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
            .param("occurredAt", if (payload is TemporalEventPayload) payload.occurredAt else payload.recordedAt)
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
            occurredAt = if (payload is TemporalEventPayload) payload.occurredAt else payload.recordedAt,
            recordedAt = payload.recordedAt,
        )
    }

    override fun latestPayload(workspaceId: UUID, aggregateId: UUID): EventPayload? = jdbc
        .sql(SELECT_LATEST_BY_AGGREGATE)
        .param("workspaceId", workspaceId)
        .param("aggregateId", aggregateId)
        .query(String::class.java)
        .optional()
        .map { mapper.readValue(it, EventPayload::class.java) }
        .orElse(null)

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

        const val SELECT_LATEST_BY_AGGREGATE = """
            SELECT payload FROM t_events
            WHERE workspace_id = :workspaceId AND aggregate_id = :aggregateId
            ORDER BY id DESC
            LIMIT 1
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