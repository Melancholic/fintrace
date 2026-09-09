package com.github.melancholic.fintrace.core.dao

import com.github.melancholic.fintrace.core.api.v1.dto.CreateWorkspaceRequest
import com.github.melancholic.fintrace.core.api.v1.dto.EditWorkspaceRequest
import com.github.melancholic.fintrace.core.domain.entity.Workspace
import com.github.melancholic.fintrace.core.domain.entity.WorkspaceStatus
import com.github.melancholic.fintrace.core.util.SqlHelper.orderBy
import com.github.melancholic.fintrace.core.util.TimestampProvider
import com.github.melancholic.fintrace.core.util.UUIDGenerator
import org.springframework.data.domain.Pageable
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.*

interface WorkspaceDAO {

    fun create(userId: UUID, request: CreateWorkspaceRequest): UUID

    fun get(userId: UUID, workspaceId: UUID): Optional<Workspace>

    fun search(userId: UUID, page: Pageable): List<Workspace>

    fun update(
        userId: UUID,
        workspaceId: UUID,
        request: EditWorkspaceRequest,
        version: Long
    ): Int

    fun changeStatus(
        userId: UUID,
        workspaceId: UUID,
        sourceStatuses: Set<WorkspaceStatus>,
        newStatus: WorkspaceStatus
    ): Boolean

    fun changeStatus(
        userId: UUID,
        workspaceId: UUID,
        sourceStatuses: Set<WorkspaceStatus>,
        newStatus: WorkspaceStatus,
        version: Long?
    ): Boolean
}

@Repository
class WorkspaceDAOImpl(
    private val jdbc: JdbcClient,
    private val uuidGenerator: UUIDGenerator,
    private val timestampProvider: TimestampProvider
) : WorkspaceDAO {

    override fun create(
        userId: UUID,
        request: CreateWorkspaceRequest
    ): UUID {
        val workspaceId = jdbc.sql(CREATE_WORKSPACE)
            .param("id", uuidGenerator.nextUUID())
            .param("name", request.workspaceName)
            .param("status", WorkspaceStatus.NEW.name)
            .param("ownerId", userId)
            .param("defaultCurrency", request.defaultCurrency)
            .param("createdAt", timestampProvider.now())
            .query(UUID::class.java)
            .single()

        return workspaceId
    }

    override fun get(
        userId: UUID,
        workspaceId: UUID
    ): Optional<Workspace> {
        return jdbc.sql(GET_BY_ID_AND_OWNER_ID)
            .param("id", workspaceId)
            .param("ownerId", userId)
            .query(Workspace::class.java)
            .optional()
    }

    @Suppress("UNCHECKED_CAST")
    override fun search(
        userId: UUID,
        page: Pageable
    ): List<Workspace> {
        return jdbc.sql(SEARCH_WORKSPACE_PER_OWNER.format(orderBy(SORTABLE_COLS, listOf("created_at DESC"), page.sort)))
            .param("ownerId", userId)
            .param("limit", page.pageSize)
            .param("offset", page.offset)
            .query(Workspace::class.java)
            .list() as List<Workspace>
    }

    override fun update(
        userId: UUID,
        workspaceId: UUID,
        request: EditWorkspaceRequest,
        version: Long
    ): Int {

        val queryBuilder = jdbc.sql(updateSql(request))
            .param("ownerId", userId)
            .param("id", workspaceId)
            .param("updatedAt", timestampProvider.now())
            .param("version", version)

        request.workspaceName?.let { queryBuilder.param("name", it) }
        request.defaultCurrency?.let { queryBuilder.param("defaultCurrency", it) }

        return queryBuilder.update()
    }

    override fun changeStatus(
        userId: UUID,
        workspaceId: UUID,
        sourceStatuses: Set<WorkspaceStatus>,
        newStatus: WorkspaceStatus
    ): Boolean {
        return changeStatus(userId, workspaceId, sourceStatuses, newStatus, null)
    }

    override fun changeStatus(
        userId: UUID,
        workspaceId: UUID,
        sourceStatuses: Set<WorkspaceStatus>,
        newStatus: WorkspaceStatus,
        version: Long?
    ): Boolean {
        val sql = if (version == null) CHANGE_STATUS else "$CHANGE_STATUS AND version = :version"

        val builder = jdbc.sql(sql)
            .param("ownerId", userId)
            .param("id", workspaceId)
            .param("updatedAt", timestampProvider.now())
            .param("newStatus", newStatus.name)
            .param("sourceStatuses", sourceStatuses.map { it.name })

        if (version != null) {
            builder.param("version", version)
        }

        val updated = builder.update()
        if (updated > 1) {
            throw IllegalStateException("Updated status change failed: were resolved more than 1 records for update (found: $updated)")
        }
        return updated == 1
    }

    private fun updateSql(request: EditWorkspaceRequest): String = buildString {
        append("UPDATE t_workspaces SET")
        request.workspaceName?.let { append(" name = :name,") }
        request.defaultCurrency?.let { append(" default_currency = :defaultCurrency,") }
        append(" updated_at = :updatedAt,")
        append(" version = version + 1")
        append(" WHERE id = :id")
        append(" AND owner_id = :ownerId")
        append(" AND status <> 'DELETED'")
        append(" AND version = :version")
    }

    companion object {
        val SORTABLE_COLS = mapOf(
            "name" to "name",
            "status" to "status",
            "createdAt" to "created_at",
        )

        const val CREATE_WORKSPACE = """
            INSERT INTO t_workspaces (id, name, status, owner_id, default_currency, created_at, updated_at, version)
            VALUES (:id, :name, :status, :ownerId, :defaultCurrency, :createdAt, :createdAt, 0)
            RETURNING id       
        """

        const val GET_BY_ID_AND_OWNER_ID = """
            SELECT * 
            FROM t_workspaces
            WHERE id = :id
            AND owner_id = :ownerId
            AND status <> 'DELETED'
        """

        const val SEARCH_WORKSPACE_PER_OWNER = """
            SELECT *
            FROM t_workspaces
            WHERE owner_id = :ownerId 
            AND status <> 'DELETED'
            ORDER BY %s
            LIMIT :limit OFFSET :offset
        """

        const val CHANGE_STATUS = """
            UPDATE t_workspaces
            SET status = :newStatus,
            updated_at = :updatedAt,
            deleted_at = CASE WHEN :newStatus = 'DELETED' THEN :updatedAt END,
            version = version + 1
            WHERE id = :id
            AND owner_id = :ownerId
            AND status <> 'DELETED'
            AND status in (:sourceStatuses)
        """
    }
}