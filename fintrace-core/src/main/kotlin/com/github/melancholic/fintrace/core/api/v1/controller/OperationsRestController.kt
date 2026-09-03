package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.api.v1.dto.CreateOperationResponse
import com.github.melancholic.fintrace.core.api.v1.dto.OperationRequest
import com.github.melancholic.fintrace.core.api.v1.dto.OperationResponse
import com.github.melancholic.fintrace.core.api.v1.mapper.OperationMapper
import com.github.melancholic.fintrace.core.config.OPERATIONS_AREA_API_PATH
import com.github.melancholic.fintrace.core.domain.command.CancelOperationCommand
import com.github.melancholic.fintrace.core.domain.command.CreateOperationCommand
import com.github.melancholic.fintrace.core.domain.command.ReviseOperationCommand
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.facade.CommandFacade
import com.github.melancholic.fintrace.core.facade.ProjectionFacade
import com.github.melancholic.fintrace.core.util.TimestampProvider
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
    private val mapper: OperationMapper,
    private val timestampProvider: TimestampProvider,
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
        @RequestBody request: OperationRequest
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

    @Operation(
        summary = "Replace an operation",
        description = "The body carries the operation's complete new state, not the fields that " +
                "changed: an absent field is not \"unchanged\". `occurredAt` may be corrected " +
                "freely, including into the past. The revision is recorded internally as a new " +
                "event; the client never sees revisions.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Revised"),
        ApiResponse(responseCode = "400", description = "Malformed body, or `occurredAt` in the future"),
        ApiResponse(
            responseCode = "404",
            description = "No such operation in this workspace — including one that exists in another",
        ),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @PutMapping("/{operationId}")
    fun reviseOperation(
        @PathVariable("workspaceId") workspaceId: UUID,
        @PathVariable("operationId") operationId: UUID,
        @RequestBody request: OperationRequest
    ): ResponseEntity<Void> {
        commandFacade.processCommand(
            ReviseOperationCommand(
                workspaceId = workspaceId,
                operationId = operationId,
                occurredAt = request.occurredAt,
                amount = request.amount
            )
        )

        return ResponseEntity.noContent().build()
    }

    @Operation(
        summary = "Cancel an operation",
        description = "Cancelling removes the operation from listings and statistics entirely — " +
                "it is not shown struck through. The history remains in the event log but is not " +
                "exposed by the API.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Cancelled"),
        ApiResponse(
            responseCode = "404",
            description = "No such operation in this workspace, including one already cancelled",
        ),
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
                operationId = operationId,
                occurredAt = timestampProvider.now()
            )
        )

        return ResponseEntity.noContent().build()
    }
}