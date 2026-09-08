package com.github.melancholic.fintrace.core.api.v1.dto

import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import com.github.melancholic.fintrace.core.domain.entity.CategorySystemCode
import com.github.melancholic.fintrace.core.validation.ValidationConstants.CATEGORY_NAME_PATTERN
import com.github.melancholic.fintrace.core.validation.ValidationConstants.MAX_ICON_LENGTH
import com.github.melancholic.fintrace.core.validation.ValidationConstants.MAX_NAME_LENGTH
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.hibernate.validator.constraints.Length
import java.time.LocalDateTime
import java.util.*

data class CreateCategoryRequest(
    @Length(max = MAX_NAME_LENGTH)
    @NotBlank
    @Pattern(regexp = CATEGORY_NAME_PATTERN)
    val name: String,
    val parentId: UUID,
    @Length(max = MAX_ICON_LENGTH)
    val icon: String?
)

data class UpdateCategoryRequest(
    @Length(max = MAX_NAME_LENGTH)
    @NotBlank
    @Pattern(regexp = CATEGORY_NAME_PATTERN)
    val name: String,
    val parentId: UUID,
    @Length(max = MAX_ICON_LENGTH)
    val icon: String?
)

data class CategoryResponse(
    val id: UUID,
    val workspaceId: UUID,
    val parentId: UUID?,
    val name: String,
    val kind: CategoryKind,
    val archived: Boolean,
    val systemCode: CategorySystemCode?,
    val icon: String?,
    val recordedAt: LocalDateTime
)
