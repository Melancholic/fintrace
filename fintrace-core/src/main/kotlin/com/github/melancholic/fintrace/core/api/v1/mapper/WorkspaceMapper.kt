package com.github.melancholic.fintrace.core.api.v1.mapper

import com.github.melancholic.fintrace.core.api.v1.dto.WorkspaceResponse
import com.github.melancholic.fintrace.core.domain.entity.Workspace
import org.mapstruct.Mapper

@Mapper(componentModel = "spring")
interface WorkspaceMapper {
    fun toResponse(workspace: Workspace): WorkspaceResponse
    fun toResponseList(workspaces: List<Workspace>): List<WorkspaceResponse>
}
