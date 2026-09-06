package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.api.v1.dto.CreateWorkspaceRequest
import com.github.melancholic.fintrace.core.api.v1.dto.EditWorkspaceRequest
import com.github.melancholic.fintrace.core.api.v1.dto.WorkspaceResponse
import com.github.melancholic.fintrace.core.api.v1.mapper.WorkspaceMapper
import com.github.melancholic.fintrace.core.domain.entity.Workspace
import com.github.melancholic.fintrace.core.security.IdentityProvider
import com.github.melancholic.fintrace.core.service.WorkspaceService
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

interface WorkspaceFacade {
    fun createWorkspace(request: CreateWorkspaceRequest): Workspace
    fun getWorkspace(workspaceId: UUID): Workspace
    fun getWorkspaces(page: Pageable): List<Workspace>
    fun editWorkspace(workspaceId: UUID, request: EditWorkspaceRequest): WorkspaceResponse
    fun archiveWorkspace(workspaceId: UUID): WorkspaceResponse
    fun unarchiveWorkspace(workspaceId: UUID): WorkspaceResponse
    fun deleteWorkspace(workspaceId: UUID, version: Long)
}

@Service
@Transactional
class WorkspaceFacadeImpl(
    private val workspaceService: WorkspaceService,
    private val identityProvider: IdentityProvider,
    private val workspaceMapper: WorkspaceMapper
) : WorkspaceFacade {

    override fun createWorkspace(request: CreateWorkspaceRequest): Workspace {
        return workspaceService.createWorkspace(identityProvider.currentUserId(), request)
    }

    @Transactional(readOnly = true)
    override fun getWorkspace(workspaceId: UUID): Workspace {
        return workspaceService.getWorkspace(identityProvider.currentUserId(), workspaceId)
    }

    @Transactional(readOnly = true)
    override fun getWorkspaces(page: Pageable): List<Workspace> {
        return workspaceService.getWorkspaces(identityProvider.currentUserId(), page)
    }

    override fun editWorkspace(workspaceId: UUID, request: EditWorkspaceRequest): WorkspaceResponse {
        workspaceService.editWorkspace(identityProvider.currentUserId(), workspaceId, request)
        val workspace = workspaceService.getWorkspace(identityProvider.currentUserId(), workspaceId)
        return workspaceMapper.toResponse(workspace)
    }

    override fun archiveWorkspace(workspaceId: UUID) : WorkspaceResponse {
        workspaceService.archiveWorkspace(identityProvider.currentUserId(), workspaceId)
        val workspace = workspaceService.getWorkspace(identityProvider.currentUserId(), workspaceId)
        return workspaceMapper.toResponse(workspace)
    }

    override fun unarchiveWorkspace(workspaceId: UUID): WorkspaceResponse {
        workspaceService.unarchiveWorkspace(identityProvider.currentUserId(), workspaceId)
        val workspace = workspaceService.getWorkspace(identityProvider.currentUserId(), workspaceId)
        return workspaceMapper.toResponse(workspace)
    }

    override fun deleteWorkspace(workspaceId: UUID, version: Long) {
        return workspaceService.deleteWorkspace(identityProvider.currentUserId(), workspaceId, version)
    }
}