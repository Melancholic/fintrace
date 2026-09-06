package com.github.melancholic.fintrace.core.service

import com.github.melancholic.fintrace.core.api.v1.dto.CreateWorkspaceRequest
import com.github.melancholic.fintrace.core.api.v1.dto.EditWorkspaceRequest
import com.github.melancholic.fintrace.core.dao.WorkspaceDAO
import com.github.melancholic.fintrace.core.domain.entity.Workspace
import com.github.melancholic.fintrace.core.domain.entity.WorkspaceStatus
import com.github.melancholic.fintrace.core.exception.ActionConflictException
import com.github.melancholic.fintrace.core.exception.ApplicationException
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.exception.OperationNotAllowedException
import com.github.melancholic.fintrace.core.validation.WorkspaceValidationService
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.*


interface WorkspaceService {
    fun createWorkspace(userId: UUID, request: CreateWorkspaceRequest): UUID
    fun getWorkspace(userId: UUID, workspaceId: UUID): Workspace
    fun getWorkspaces(userId: UUID, page: Pageable): List<Workspace>
    fun editWorkspace(userId: UUID, workspaceId: UUID, request: EditWorkspaceRequest)
    fun archiveWorkspace(userId: UUID, workspaceId: UUID)
    fun unarchiveWorkspace(userId: UUID, workspaceId: UUID)
    fun deleteWorkspace(userId: UUID, workspaceId: UUID, version: Long)
    fun activateWorkspace(userId: UUID, workspaceId: UUID): Boolean
    fun requireWritable(userId: UUID, workspaceId: UUID): Workspace
    fun requireReadable(userId: UUID, workspaceId: UUID): Workspace
}

@Service
@Transactional(propagation = Propagation.MANDATORY)
class WorkspaceServiceImpl(
    private val workspaceDAO: WorkspaceDAO,
    private val validationService: WorkspaceValidationService
) : WorkspaceService {

    override fun createWorkspace(
        userId: UUID,
        request: CreateWorkspaceRequest
    ): UUID {
        validationService.validate(request)
        return workspaceDAO.create(userId, request)
    }

    override fun getWorkspace(
        userId: UUID,
        workspaceId: UUID
    ): Workspace {
        return workspaceDAO.get(userId, workspaceId)
            .orElseThrow { NotFoundEntityException("Workspace not found (workspaceId='$workspaceId')") }
    }

    override fun getWorkspaces(
        userId: UUID,
        page: Pageable
    ): List<Workspace> {
        return workspaceDAO.search(userId, page)
    }

    override fun editWorkspace(
        userId: UUID,
        workspaceId: UUID,
        request: EditWorkspaceRequest
    ) {
        validationService.validate(request)
        val updated = workspaceDAO.update(userId, workspaceId, request, request.version)
        when {
            updated > 1 -> throw ApplicationException("Couldn't update workspace: unexpected number of updated records: $updated, operation was canceled")
            updated < 1 -> {
                val workspace = getWorkspace(userId, workspaceId)
                throw ActionConflictException("Couldn't update workspace: ${workspace.name}, operation was canceled")
            }
        }
    }

    override
    fun archiveWorkspace(userId: UUID, workspaceId: UUID) {
        if (!workspaceDAO.changeStatus(
                userId,
                workspaceId,
                setOf(WorkspaceStatus.ACTIVE),
                WorkspaceStatus.ARCHIVED
            )
        ) {
            val workspace = getWorkspace(userId, workspaceId)
            if (workspace.status != WorkspaceStatus.ARCHIVED) {
                throw ActionConflictException(STATUS_CONFLICT_MSG)
            }
        }

    }

    override fun unarchiveWorkspace(userId: UUID, workspaceId: UUID) {
        if (!workspaceDAO.changeStatus(
                userId,
                workspaceId,
                setOf(WorkspaceStatus.ARCHIVED),
                WorkspaceStatus.ACTIVE
            )
        ) {
            val workspace = getWorkspace(userId, workspaceId)
            if (workspace.status != WorkspaceStatus.ACTIVE) {
                throw ActionConflictException(STATUS_CONFLICT_MSG)
            }
        }
    }

    override fun deleteWorkspace(userId: UUID, workspaceId: UUID, version: Long) {
        if (!workspaceDAO.changeStatus(
                userId,
                workspaceId,
                TO_DELETE_STATUSES,
                WorkspaceStatus.DELETED,
                version
            )
        ) {
            val workspace = getWorkspace(userId, workspaceId)
            throw ActionConflictException(
                if (workspace.version != version) "Workspace changed since it was read"
                else "Cannot delete a workspace in status ${workspace.status}"
            )
        }
    }

    override fun activateWorkspace(userId: UUID, workspaceId: UUID): Boolean {
        return workspaceDAO.changeStatus(userId, workspaceId, setOf(WorkspaceStatus.NEW), WorkspaceStatus.ACTIVE)
    }

    override fun requireWritable(
        userId: UUID,
        workspaceId: UUID
    ): Workspace {
        val workspace = getWorkspace(userId, workspaceId)
        if (!WRITABLE_STATUSES.contains(workspace.status)) {
            throw OperationNotAllowedException("Operations from workspace '${workspace.id}' not allowed to write")
        }
        return workspace
    }

    override fun requireReadable(userId: UUID, workspaceId: UUID) : Workspace {
        val workspace = getWorkspace(userId, workspaceId)
        if (!READABLE_STATUSES.contains(workspace.status)) {
            throw OperationNotAllowedException("Operations from workspace '${workspace.id}' not allowed to read")
        }
        return workspace
    }

    companion object {
        val TO_DELETE_STATUSES = setOf(WorkspaceStatus.NEW, WorkspaceStatus.ACTIVE, WorkspaceStatus.ARCHIVED)
        val WRITABLE_STATUSES = setOf(WorkspaceStatus.NEW, WorkspaceStatus.ACTIVE)
        val READABLE_STATUSES = setOf(WorkspaceStatus.NEW, WorkspaceStatus.ACTIVE, WorkspaceStatus.ARCHIVED)
        const val STATUS_CONFLICT_MSG = "Couldn't change workspace status due statuses conflict"
    }
}