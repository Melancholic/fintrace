package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.api.v1.dto.CreateOperationRequest
import com.github.melancholic.fintrace.core.api.v1.dto.OperationResponse
import com.github.melancholic.fintrace.core.api.v1.dto.UpdateOperationRequest
import com.github.melancholic.fintrace.core.api.v1.mapper.OperationMapper
import com.github.melancholic.fintrace.core.config.OPERATIONS_AREA_API_PATH
import com.github.melancholic.fintrace.core.domain.command.CancelOperationCommand
import com.github.melancholic.fintrace.core.domain.command.CreateOperationCommand
import com.github.melancholic.fintrace.core.domain.command.ReviseOperationCommand
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.facade.CommandFacade
import com.github.melancholic.fintrace.core.facade.ProjectionFacade
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder
import java.util.*

@RestController
@RequestMapping(OPERATIONS_AREA_API_PATH)
@Tag(name = "Operations")
class OperationsRestController(
    private val commandFacade: CommandFacade,
    private val projectionFacade: ProjectionFacade,
    private val mapper: OperationMapper
) {

    @Operation(summary = "Record an operation")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Created; `Location` points at it"),
        ApiResponse(responseCode = "400", description = "Malformed body, or `occurredAt` in the future"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @PostMapping
    fun createNewOperation(
        @PathVariable("workspaceId") workspaceId: UUID,
        @Valid @RequestBody request: CreateOperationRequest
    ): ResponseEntity<OperationResponse> {
        val projection: OperationProjection = commandFacade.processCommand(
            CreateOperationCommand(
                workspaceId = workspaceId,
                occurredAt = request.occurredAt,
                accountId = request.accountId,
                amount = request.amount,
                kind = request.kind,
                categoryId = request.categoryId,
                comment = request.comment
            )
        )

        val location = MvcUriComponentsBuilder
            .fromController(OperationsRestController::class.java)
            .path("/{operationId}")
            .buildAndExpand(workspaceId, projection.id)
            .toUri()

        return ResponseEntity
            .created(location)
            .body(mapper.toResponse(projection))
    }

    @Operation(summary = "Fetch an operation")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Found"),
        ApiResponse(responseCode = "404", description = "No such operation in this workspace"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @GetMapping("/{operationId}")
    fun getOperation(
        @PathVariable("workspaceId") workspaceId: UUID,
        @PathVariable("operationId") operationId: UUID,
    ): ResponseEntity<OperationResponse> {
        val projection: OperationProjection = projectionFacade.getOperation(workspaceId, operationId)

        return ResponseEntity
            .ok(mapper.toResponse(projection))
    }

    @Operation(summary = "Replace an operation")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Revised; body carries the new state"),
        ApiResponse(responseCode = "400", description = "Malformed body, or `occurredAt` in the future"),
        ApiResponse(responseCode = "404", description = "No such operation in this workspace"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @PutMapping("/{operationId}")
    fun reviseOperation(
        @PathVariable("workspaceId") workspaceId: UUID,
        @PathVariable("operationId") operationId: UUID,
        @Valid @RequestBody request: UpdateOperationRequest
    ): ResponseEntity<OperationResponse> {
        val projection = commandFacade.processCommand(
            ReviseOperationCommand(
                workspaceId = workspaceId,
                operationId = operationId,
                occurredAt = request.occurredAt,
                accountId = request.accountId,
                amount = request.amount,
                kind = request.kind,
                categoryId = request.categoryId,
                comment = request.comment
            )
        )

        return ResponseEntity.ok(mapper.toResponse(projection))
    }

    @Operation(summary = "Cancel an operation")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Cancelled"),
        ApiResponse(responseCode = "404", description = "No such operation, including one already cancelled"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @DeleteMapping("/{operationId}")
    fun cancelOperation(
        @PathVariable("workspaceId") workspaceId: UUID,
        @PathVariable("operationId") operationId: UUID,
    ): ResponseEntity<Void> {
        commandFacade.processCommand(
            CancelOperationCommand(
                workspaceId = workspaceId,
                operationId = operationId
            )
        )

        return ResponseEntity.noContent().build()
    }
}