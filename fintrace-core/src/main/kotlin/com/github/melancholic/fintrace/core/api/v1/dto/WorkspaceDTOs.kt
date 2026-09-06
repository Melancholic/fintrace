package com.github.melancholic.fintrace.core.api.v1.dto

import com.github.melancholic.fintrace.core.domain.entity.WorkspaceStatus
import com.github.melancholic.fintrace.core.validation.ValidationConstants.CURRENCY_PATTERN
import com.github.melancholic.fintrace.core.validation.ValidationConstants.MAX_NAME_LENGTH
import com.github.melancholic.fintrace.core.validation.ValidationConstants.WORKSPACE_NAME_PATTERN
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.PositiveOrZero
import org.hibernate.validator.constraints.Length
import java.time.LocalDateTime
import java.util.*

data class CreateWorkspaceRequest(
    @Length(max = MAX_NAME_LENGTH)
    @NotBlank
    @Pattern(regexp = WORKSPACE_NAME_PATTERN)
    val workspaceName: String,

    @NotBlank
    @Pattern(regexp = CURRENCY_PATTERN)
    val defaultCurrency: String,
)

data class EditWorkspaceRequest(
    @NotNull
    @PositiveOrZero
    val version: Long,

    @Length(max = MAX_NAME_LENGTH)
    @Pattern(regexp = WORKSPACE_NAME_PATTERN)
    val workspaceName: String?,

    @Pattern(regexp = CURRENCY_PATTERN)
    val defaultCurrency: String?,
)

data class WorkspaceResponse(
    val id: UUID,
    val name: String,
    val status: WorkspaceStatus,
    val defaultCurrency: String,
    val version: Long,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val deletedAt: LocalDateTime?
)