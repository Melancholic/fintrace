package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.api.v1.dto.BalanceAnchorResponse
import com.github.melancholic.fintrace.core.api.v1.dto.CreateBalanceAnchorRequest
import com.github.melancholic.fintrace.core.api.v1.mapper.BalanceAnchorMapper
import com.github.melancholic.fintrace.core.config.ACCOUNT_BALANCE_ANCHORS_AREA_API_PATH
import com.github.melancholic.fintrace.core.domain.command.CancelBalanceAnchorCommand
import com.github.melancholic.fintrace.core.domain.command.CreateBalanceAnchorCommand
import com.github.melancholic.fintrace.core.domain.projection.BalanceAnchorProjection
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
@RequestMapping(ACCOUNT_BALANCE_ANCHORS_AREA_API_PATH)
@Tag(name = "Balance Anchors")
class BalanceAnchorsRestController(
    private val commandFacade: CommandFacade,
    private val projectionFacade: ProjectionFacade,
    private val mapper: BalanceAnchorMapper
) {

    @Operation(summary = "Record a balance anchor")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Created; `Location` points at it"),
        ApiResponse(responseCode = "400", description = "Malformed body"),
        ApiResponse(responseCode = "404", description = "No such account in this workspace"),
        ApiResponse(responseCode = "409", description = "The account is archived, or the workspace is"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @PostMapping
    fun createNewBalanceAnchor(
        @PathVariable("workspaceId") workspaceId: UUID,
        @PathVariable("accountId") accountId: UUID,
        @Valid @RequestBody request: CreateBalanceAnchorRequest
    ): ResponseEntity<BalanceAnchorResponse> {
        val projection: BalanceAnchorProjection = commandFacade.processCommand(
            CreateBalanceAnchorCommand(
                workspaceId = workspaceId,
                accountId = accountId,
                value = request.value
            )
        )

        val location = MvcUriComponentsBuilder
            .fromController(BalanceAnchorsRestController::class.java)
            .path("/{anchorId}")
            .buildAndExpand(workspaceId, accountId, projection.id)
            .toUri()

        return ResponseEntity
            .created(location)
            .body(mapper.toResponse(projection))
    }

    @Operation(summary = "List an account's balance anchors")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Newest first; empty when the account has none"),
        ApiResponse(responseCode = "404", description = "No such account in this workspace"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @GetMapping
    fun getAllBalanceAnchors(
        @PathVariable("workspaceId") workspaceId: UUID,
        @PathVariable("accountId") accountId: UUID
    ): ResponseEntity<List<BalanceAnchorResponse>> {
        val projectionList: List<BalanceAnchorProjection> =
            projectionFacade.getAllBalanceAnchors(workspaceId, accountId)
        return ResponseEntity
            .ok(mapper.toResponseList(projectionList))
    }

    @Operation(summary = "Fetch a balance anchor")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Found"),
        ApiResponse(responseCode = "404", description = "No such anchor on this account"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @GetMapping("/{anchorId}")
    fun getBalanceAnchor(
        @PathVariable("workspaceId") workspaceId: UUID,
        @PathVariable("accountId") accountId: UUID,
        @PathVariable("anchorId") anchorId: UUID
    ): ResponseEntity<BalanceAnchorResponse> {
        val projection: BalanceAnchorProjection = projectionFacade.getBalanceAnchor(workspaceId, accountId, anchorId)
        return ResponseEntity
            .ok(mapper.toResponse(projection))
    }


    @Operation(summary = "Delete a balance anchor")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Deleted; the row is removed, the event remains"),
        ApiResponse(responseCode = "404", description = "No such anchor on this account"),
        ApiResponse(
            responseCode = "409",
            description = "Not the account's most recent anchor, or the workspace is archived"
        ),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @DeleteMapping("/{anchorId}")
    fun deleteBalanceAnchor(
        @PathVariable("workspaceId") workspaceId: UUID,
        @PathVariable("accountId") accountId: UUID,
        @PathVariable("anchorId") anchorId: UUID
    ): ResponseEntity<Void> {
        commandFacade.processCommand(
            CancelBalanceAnchorCommand(
                workspaceId = workspaceId,
                accountId = accountId,
                anchorId = anchorId
            )
        )
        return ResponseEntity.noContent().build()
    }
}