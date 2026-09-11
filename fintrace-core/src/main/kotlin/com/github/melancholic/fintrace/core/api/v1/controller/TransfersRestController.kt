package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.api.v1.dto.CreateTransferRequest
import com.github.melancholic.fintrace.core.api.v1.dto.TransferResponse
import com.github.melancholic.fintrace.core.api.v1.dto.UpdateTransferRequest
import com.github.melancholic.fintrace.core.api.v1.mapper.TransferMapper
import com.github.melancholic.fintrace.core.config.TRANSFERS_AREA_API_PATH
import com.github.melancholic.fintrace.core.domain.command.CancelTransferCommand
import com.github.melancholic.fintrace.core.domain.entity.Transfer
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
@RequestMapping(TRANSFERS_AREA_API_PATH)
@Tag(name = "Transfers")
class TransfersRestController(
    private val commandFacade: CommandFacade,
    private val projectionFacade: ProjectionFacade,
    private val mapper: TransferMapper
) {

    @Operation(summary = "Record a transfer")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Created; `Location` points at it"),
        ApiResponse(
            responseCode = "400",
            description = "Malformed body, `occurredAt` in the future, an amount that is not positive, or both legs on one account"
        ),
        ApiResponse(responseCode = "404", description = "No such account in this workspace"),
        ApiResponse(responseCode = "409", description = "An account is archived, or the workspace is"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @PostMapping
    fun createTransfer(
        @PathVariable("workspaceId") workspaceId: UUID,
        @Valid @RequestBody request: CreateTransferRequest
    ): ResponseEntity<TransferResponse> {
        val transfer: Transfer = commandFacade.processCommand(mapper.toCommand(workspaceId, request))

        val location = MvcUriComponentsBuilder
            .fromController(TransfersRestController::class.java)
            .path("/{transferId}")
            .buildAndExpand(workspaceId, transfer.id)
            .toUri()

        return ResponseEntity
            .created(location)
            .body(mapper.toResponse(transfer))
    }

    @Operation(summary = "Fetch a transfer")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Found; both legs, each in its account's currency"),
        ApiResponse(responseCode = "404", description = "No such transfer in this workspace"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @GetMapping("/{transferId}")
    fun getTransfer(
        @PathVariable("workspaceId") workspaceId: UUID,
        @PathVariable("transferId") transferId: UUID,
    ): ResponseEntity<TransferResponse> {
        val transfer: Transfer = projectionFacade.getTransfer(workspaceId, transferId)

        return ResponseEntity
            .ok(mapper.toResponse(transfer))
    }

    @Operation(summary = "Revise a transfer")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Revised; body carries the new state"),
        ApiResponse(
            responseCode = "400",
            description = "Malformed body, `occurredAt` in the future, an amount that is not positive, or both legs on one account"
        ),
        ApiResponse(responseCode = "404", description = "No such transfer or account in this workspace"),
        ApiResponse(
            responseCode = "409",
            description = "A leg would move onto an archived account, or the workspace is archived"
        ),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @PutMapping("/{transferId}")
    fun reviseTransfer(
        @PathVariable("workspaceId") workspaceId: UUID,
        @PathVariable("transferId") transferId: UUID,
        @Valid @RequestBody request: UpdateTransferRequest
    ): ResponseEntity<TransferResponse> {
        val transfer: Transfer = commandFacade.processCommand(mapper.toCommand(workspaceId, transferId, request))
        return ResponseEntity
            .ok(mapper.toResponse(transfer))
    }

    @Operation(summary = "Cancel a transfer")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Cancelled; both legs removed"),
        ApiResponse(responseCode = "404", description = "No such transfer, including one already cancelled"),
        ApiResponse(responseCode = "409", description = "Workspace is archived"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @DeleteMapping("/{transferId}")
    fun cancelTransfer(
        @PathVariable("workspaceId") workspaceId: UUID,
        @PathVariable("transferId") transferId: UUID,
    ): ResponseEntity<Void> {
        commandFacade.processCommand(
            CancelTransferCommand(
                workspaceId = workspaceId,
                transferId = transferId
            )
        )

        return ResponseEntity.noContent().build()
    }

}