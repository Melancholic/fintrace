package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.api.v1.dto.CategoryResponse
import com.github.melancholic.fintrace.core.api.v1.dto.CreateCategoryRequest
import com.github.melancholic.fintrace.core.api.v1.dto.UpdateCategoryRequest
import com.github.melancholic.fintrace.core.api.v1.mapper.CategoryMapper
import com.github.melancholic.fintrace.core.config.CATEGORIES_AREA_API_PATH
import com.github.melancholic.fintrace.core.domain.command.SetCategoryArchivedCommand
import com.github.melancholic.fintrace.core.domain.projection.CategoryProjection
import com.github.melancholic.fintrace.core.facade.CategoryFacade
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
@RequestMapping(CATEGORIES_AREA_API_PATH)
@Tag(name = "Categories")
class CategoriesRestController(
    private val facade: CategoryFacade,
    private val projectionFacade: ProjectionFacade,
    private val mapper: CategoryMapper,
    private val commandFacade: CommandFacade
) {

    @Operation(summary = "Create a category")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Created; `kind` comes from the parent's branch"),
        ApiResponse(responseCode = "400", description = "Malformed body, or no such parent"),
        ApiResponse(responseCode = "404", description = "No such workspace for this caller"),
        ApiResponse(responseCode = "409", description = "Parent is archived, or is an Others"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @PostMapping
    fun createNewCategory(
        @PathVariable("workspaceId") workspaceId: UUID,
        @Valid @RequestBody request: CreateCategoryRequest
    ): ResponseEntity<CategoryResponse> {
        val category = facade.create(workspaceId, request)

        val location = MvcUriComponentsBuilder
            .fromController(CategoriesRestController::class.java)
            .path("/{categoryId}")
            .buildAndExpand(workspaceId, category.id)
            .toUri()

        return ResponseEntity
            .created(location)
            .body(mapper.toResponse(category))
    }

    @Operation(summary = "Fetch a category")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Found, archived or not"),
        ApiResponse(responseCode = "404", description = "No such category in this workspace"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @GetMapping("/{categoryId}")
    fun getCategory(
        @PathVariable("workspaceId") workspaceId: UUID,
        @PathVariable("categoryId") categoryId: UUID
    ): ResponseEntity<CategoryResponse> {
        val projection: CategoryProjection = projectionFacade.getCategory(workspaceId, categoryId)
        return ResponseEntity
            .ok(mapper.toResponse(projection))
    }

    @Operation(summary = "List categories")
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Flat; build the tree from `parentId`. Archived excluded unless `includeArchived`"
        ),
        ApiResponse(responseCode = "404", description = "No such workspace for this caller"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @GetMapping
    fun getAllCategories(
        @PathVariable("workspaceId") workspaceId: UUID,
        @RequestParam(value = "includeArchived", required = false, defaultValue = "false") includeArchived: Boolean
    ): ResponseEntity<List<CategoryResponse>> {
        val projections: List<CategoryProjection> = projectionFacade.getAllCategories(workspaceId, includeArchived)
        return ResponseEntity
            .ok(mapper.toResponseList(projections))
    }

    @Operation(summary = "Replace a category's name, icon and parent")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Revised; a different `parentId` moves it"),
        ApiResponse(responseCode = "400", description = "Malformed body, or no such parent"),
        ApiResponse(responseCode = "404", description = "No such category in this workspace"),
        ApiResponse(responseCode = "409", description = "System category, archived, cross-branch move, or a cycle"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @PutMapping("/{categoryId}")
    fun reviseCategory(
        @PathVariable("workspaceId") workspaceId: UUID,
        @PathVariable("categoryId") categoryId: UUID,
        @Valid @RequestBody request: UpdateCategoryRequest
    ): ResponseEntity<CategoryResponse> {
        val projection: CategoryProjection = facade.reviseCategory(workspaceId, categoryId, request)
        return ResponseEntity.ok(mapper.toResponse(projection))
    }

    @Operation(summary = "Archive a category and its subtree")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Archived with its descendants; never deleted (§4.7)"),
        ApiResponse(responseCode = "404", description = "No such category in this workspace"),
        ApiResponse(responseCode = "409", description = "System category"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @DeleteMapping("/{categoryId}")
    fun deleteCategory(
        @PathVariable("workspaceId") workspaceId: UUID,
        @PathVariable("categoryId") categoryId: UUID,
    ): ResponseEntity<CategoryResponse> {
        val projection: CategoryProjection = commandFacade.processCommand(
            SetCategoryArchivedCommand(
                workspaceId = workspaceId,
                categoryId = categoryId,
                archived = true
            )
        )
        return ResponseEntity.ok(mapper.toResponse(projection))
    }

    @Operation(summary = "Restore an archived category")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Restored; descendants stay archived"),
        ApiResponse(responseCode = "404", description = "No such category in this workspace"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @PostMapping("/{categoryId}/restore")
    fun restoreCategory(
        @PathVariable("workspaceId") workspaceId: UUID,
        @PathVariable("categoryId") categoryId: UUID
    ): ResponseEntity<CategoryResponse> {
        val projection: CategoryProjection = commandFacade.processCommand(
            SetCategoryArchivedCommand(
                workspaceId = workspaceId,
                categoryId = categoryId,
                archived = false
            )
        )
        return ResponseEntity.ok(mapper.toResponse(projection))
    }
}