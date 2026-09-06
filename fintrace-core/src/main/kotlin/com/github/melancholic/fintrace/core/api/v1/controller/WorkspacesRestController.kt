package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.api.v1.dto.CreateWorkspaceRequest
import com.github.melancholic.fintrace.core.api.v1.dto.EditWorkspaceRequest
import com.github.melancholic.fintrace.core.api.v1.dto.WorkspaceResponse
import com.github.melancholic.fintrace.core.api.v1.mapper.WorkspaceMapper
import com.github.melancholic.fintrace.core.config.WORKSPACES_AREA_API_PATH
import com.github.melancholic.fintrace.core.domain.entity.Workspace
import com.github.melancholic.fintrace.core.facade.WorkspaceFacade
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder
import java.util.*

@RestController
@RequestMapping(WORKSPACES_AREA_API_PATH)
@Tag(name = "Workspaces")
class WorkspacesRestController(
    private val workspaceFacade: WorkspaceFacade,
    private val mapper: WorkspaceMapper
) {

    @Operation(summary = "Create a workspace")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Created; `Location` points at it"),
        ApiResponse(responseCode = "400", description = "Malformed body, or unknown currency"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @PostMapping
    fun createNewWorkspace(
        @Valid @RequestBody request: CreateWorkspaceRequest
    ): ResponseEntity<WorkspaceResponse> {
        val workspace: Workspace = workspaceFacade.createWorkspace(request)

        val location = MvcUriComponentsBuilder
            .fromController(WorkspacesRestController::class.java)
            .path("/{workspaceId}")
            .buildAndExpand(workspace.id)
            .toUri()

        return ResponseEntity
            .created(location)
            .body(mapper.toResponse(workspace))
    }

    @Operation(summary = "Fetch a workspace")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Found"),
        ApiResponse(responseCode = "404", description = "No such workspace for this caller"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @GetMapping("/{workspaceId}")
    fun getWorkspace(
        @PathVariable("workspaceId") workspaceId: UUID
    ): ResponseEntity<WorkspaceResponse> {
        val workspace: Workspace = workspaceFacade.getWorkspace(workspaceId)

        return ResponseEntity
            .ok(mapper.toResponse(workspace))
    }

    @Operation(summary = "List the caller's workspaces")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Sortable by name, status, createdAt"),
        ApiResponse(responseCode = "400", description = "Sort requested on an unsupported field"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @GetMapping
    fun getWorkspaces(
        @PageableDefault(sort = ["createdAt"], direction = Sort.Direction.DESC) page: Pageable
    ): ResponseEntity<List<WorkspaceResponse>> {
        val workspaces: List<Workspace> = workspaceFacade.getWorkspaces(page)

        return ResponseEntity
            .ok(mapper.toResponseList(workspaces))
    }

    @Operation(summary = "Replace a workspace's name and currency")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Revised; body carries the new version"),
        ApiResponse(responseCode = "400", description = "Malformed body, or unknown currency"),
        ApiResponse(responseCode = "404", description = "No such workspace for this caller"),
        ApiResponse(responseCode = "409", description = "`version` is stale"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @PutMapping("/{workspaceId}")
    fun editWorkspace(
        @PathVariable("workspaceId") workspaceId: UUID,
        @Valid @RequestBody request: EditWorkspaceRequest
    ): ResponseEntity<WorkspaceResponse> {
        val updatedWorkspace = workspaceFacade.editWorkspace(workspaceId, request)
        return ResponseEntity.ok(updatedWorkspace)
    }

    @Operation(summary = "Archive a workspace")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Archived, or already archived"),
        ApiResponse(responseCode = "404", description = "No such workspace for this caller"),
        ApiResponse(responseCode = "409", description = "Not ACTIVE"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @PostMapping("/{workspaceId}/archive")
    fun archiveWorkspace(
        @PathVariable("workspaceId") workspaceId: UUID,
    ): ResponseEntity<WorkspaceResponse> {
        val updatedWorkspace = workspaceFacade.archiveWorkspace(workspaceId)
        return ResponseEntity.ok(updatedWorkspace)
    }

    @Operation(summary = "Unarchive a workspace")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Active, or already active"),
        ApiResponse(responseCode = "404", description = "No such workspace for this caller"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @DeleteMapping("/{workspaceId}/archive")
    fun unarchiveWorkspace(
        @PathVariable("workspaceId") workspaceId: UUID,
    ): ResponseEntity<WorkspaceResponse> {
        val updatedWorkspace = workspaceFacade.unarchiveWorkspace(workspaceId)
        return ResponseEntity.ok(updatedWorkspace)
    }


    @Operation(summary = "Delete a workspace")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Deleted; unrecoverable"),
        ApiResponse(responseCode = "400", description = "`version` missing"),
        ApiResponse(responseCode = "404", description = "No such workspace for this caller"),
        ApiResponse(responseCode = "409", description = "`version` is stale"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @DeleteMapping("/{workspaceId}")
    fun deleteWorkspace(
        @PathVariable("workspaceId") workspaceId: UUID,
        @RequestParam("version") version: Long,
    ): ResponseEntity<Void> {
        workspaceFacade.deleteWorkspace(workspaceId, version)
        return ResponseEntity.noContent().build()
    }
}