package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.api.v1.dto.CreateOperationRequest
import com.github.melancholic.fintrace.core.api.v1.dto.CreateOperationResponse
import com.github.melancholic.fintrace.core.api.v1.dto.OperationResponse
import com.github.melancholic.fintrace.core.api.v1.mapper.OperationMapper
import com.github.melancholic.fintrace.core.config.OPERATIONS_AREA_API_PATH
import com.github.melancholic.fintrace.core.domain.command.CreateOperationCommand
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.facade.CommandFacade
import com.github.melancholic.fintrace.core.facade.ProjectionFacade
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder
import java.util.*

@RestController
@RequestMapping(OPERATIONS_AREA_API_PATH)
@Tag(name = "Operations", description = "Income and expense entries within a workspace")
class OperationsRestController(
    private val commandFacade: CommandFacade,
    private val projectionFacade: ProjectionFacade,
    private val mapper: OperationMapper
) {

    @Operation(
        summary = "Record a new operation",
        description = "The amount is signed: expenses are negative. `occurredAt` is when the " +
                "operation happened and may be back-dated freely; the time it was recorded is set " +
                "by the server.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Created; `Location` points at the new operation"),
        ApiResponse(responseCode = "400", description = "Malformed request body"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @PostMapping
    fun createNewOperation(
        @PathVariable("workspaceId") workspaceId: UUID,
        @RequestBody request: CreateOperationRequest
    ): ResponseEntity<CreateOperationResponse> {
        val createdId = commandFacade.processCommand(
            CreateOperationCommand(
                workspaceId,
                request.occurredAt,
                request.amount,
            )
        )

        val location = MvcUriComponentsBuilder
            .fromController(OperationsRestController::class.java)
            .path("/{operationId}")
            .buildAndExpand(workspaceId, createdId)
            .toUri()

        return ResponseEntity
            .created(location)
            .body(CreateOperationResponse(createdId))
    }

    @Operation(summary = "Fetch a single operation")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Found"),
        ApiResponse(
            responseCode = "404",
            description = "No such operation in this workspace — including one that exists in another",
        ),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @GetMapping("/{operationId}")
    fun getOperation(
        @PathVariable("workspaceId") workspaceId: UUID,
        @PathVariable("operationId") operationId: UUID,
    ): ResponseEntity<OperationResponse> {
        val projection: OperationProjection = projectionFacade.getOperationProjection(workspaceId, operationId)

        return ResponseEntity
            .ok(mapper.toResponse(projection))
    }
}