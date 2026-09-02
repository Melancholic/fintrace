package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.config.ADMIN_API_V1_BASE_PATH
import com.github.melancholic.fintrace.core.facade.AdminFacade
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping(ADMIN_API_V1_BASE_PATH)
@Tag(name = "Admin", description = "Maintenance operations; requires the ADMIN role")
class AdminRestController(
    private val adminFacade: AdminFacade
) {

    @Operation(
        summary = "Rebuild a workspace's projections from its event log",
        description = "Clears the workspace's projection tables and replays every stored event " +
            "in order. Recovery tool: use after fixing a projection defect, or to verify that " +
            "the projection is still derivable from the log. Writes no new events.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Rebuilt; a workspace with no events is a no-op"),
        ApiResponse(responseCode = "403", description = "Not authenticated, or not an admin"),
    )
    @PostMapping("/workspaces/{workspaceId}/replay")
    fun replayWorkspace(@PathVariable("workspaceId") workspaceId: UUID): ResponseEntity<Unit> {
        adminFacade.replayWorkspace(workspaceId)
        return ResponseEntity.ok().build()
    }

}