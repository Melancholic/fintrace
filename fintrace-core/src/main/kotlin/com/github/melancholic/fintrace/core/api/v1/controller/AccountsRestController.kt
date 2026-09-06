package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.api.v1.dto.AccountResponse
import com.github.melancholic.fintrace.core.api.v1.dto.CreateAccountRequest
import com.github.melancholic.fintrace.core.api.v1.dto.UpdateAccountRequest
import com.github.melancholic.fintrace.core.api.v1.mapper.AccountMapper
import com.github.melancholic.fintrace.core.config.ACCOUNTS_AREA_API_PATH
import com.github.melancholic.fintrace.core.domain.command.CreateAccountCommand
import com.github.melancholic.fintrace.core.domain.command.ReviseAccountCommand
import com.github.melancholic.fintrace.core.domain.command.SetAccountArchivedCommand
import com.github.melancholic.fintrace.core.domain.projection.AccountProjection
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
@RequestMapping(ACCOUNTS_AREA_API_PATH)
@Tag(name = "Accounts")
class AccountsRestController(
    private val commandFacade: CommandFacade,
    private val projectionFacade: ProjectionFacade,
    private val mapper: AccountMapper
) {

    @Operation(summary = "Create an account")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Created; `Location` points at it"),
        ApiResponse(responseCode = "400", description = "Malformed body, or unknown currency"),
        ApiResponse(responseCode = "404", description = "No such workspace for this caller"),
        ApiResponse(responseCode = "409", description = "Workspace is archived"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @PostMapping
    fun createNewAccount(
        @PathVariable("workspaceId") workspaceId: UUID,
        @Valid @RequestBody request: CreateAccountRequest
    ): ResponseEntity<AccountResponse> {
        val projection: AccountProjection = commandFacade.processCommand(
            CreateAccountCommand(
                workspaceId = workspaceId,
                name = request.name,
                currency = request.currency,
                icon = request.icon,
            )
        )

        val location = MvcUriComponentsBuilder
            .fromController(AccountsRestController::class.java)
            .path("/{accountId}")
            .buildAndExpand(workspaceId, projection.id)
            .toUri()

        return ResponseEntity
            .created(location)
            .body(mapper.toResponse(projection))
    }

    @Operation(summary = "Fetch an account")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Found"),
        ApiResponse(responseCode = "404", description = "No such account in this workspace"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @GetMapping("/{accountId}")
    fun getAccount(
        @PathVariable("workspaceId") workspaceId: UUID,
        @PathVariable("accountId") accountId: UUID
    ): ResponseEntity<AccountResponse> {
        val projection: AccountProjection = projectionFacade.getAccount(workspaceId, accountId)
        return ResponseEntity
            .ok(mapper.toResponse(projection))
    }

    @Operation(summary = "List accounts")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Archived accounts excluded unless `includeArchived`"),
        ApiResponse(responseCode = "404", description = "No such workspace for this caller"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @GetMapping
    fun getAccount(
        @PathVariable("workspaceId") workspaceId: UUID,
        @RequestParam(value = "includeArchived", required = false, defaultValue = "false") includeArchived: Boolean
    ): ResponseEntity<List<AccountResponse>> {
        val projections: List<AccountProjection> = projectionFacade.getAllAccounts(workspaceId, includeArchived)
        return ResponseEntity
            .ok(mapper.toResponseList(projections))
    }

    @Operation(summary = "Replace an account's name and icon")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Revised; currency is immutable and untouched"),
        ApiResponse(responseCode = "400", description = "Malformed body"),
        ApiResponse(responseCode = "404", description = "No such account in this workspace"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @PutMapping("/{accountId}")
    fun reviseAccount(
        @PathVariable("workspaceId") workspaceId: UUID,
        @PathVariable("accountId") accountId: UUID,
        @Valid @RequestBody request: UpdateAccountRequest
    ): ResponseEntity<AccountResponse> {
        val projection: AccountProjection = commandFacade.processCommand(
            ReviseAccountCommand(
                workspaceId = workspaceId,
                accountId = accountId,
                name = request.name,
                icon = request.icon
            )
        )

        return ResponseEntity.ok(mapper.toResponse(projection))
    }

    @Operation(summary = "Archive an account")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Archived, or already archived"),
        ApiResponse(responseCode = "404", description = "No such account in this workspace"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @DeleteMapping("/{accountId}")
    fun archiveAccount(
        @PathVariable("workspaceId") workspaceId: UUID,
        @PathVariable("accountId") accountId: UUID,
    ): ResponseEntity<AccountResponse> {
        val projection: AccountProjection = commandFacade.processCommand(
            SetAccountArchivedCommand(
                workspaceId = workspaceId,
                accountId = accountId,
                archived = true
            )
        )
        return ResponseEntity.ok(mapper.toResponse(projection))
    }

    @Operation(summary = "Restore an archived account")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Active, or already active"),
        ApiResponse(responseCode = "404", description = "No such account in this workspace"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @PostMapping("/{accountId}/restore")
    fun restoreAccount(
        @PathVariable("workspaceId") workspaceId: UUID,
        @PathVariable("accountId") accountId: UUID
    ): ResponseEntity<AccountResponse> {
        val projection: AccountProjection = commandFacade.processCommand(
            SetAccountArchivedCommand(
                workspaceId = workspaceId,
                accountId = accountId,
                archived = false
            )
        )
        return ResponseEntity.ok(mapper.toResponse(projection))
    }
}