package com.github.melancholic.fintrace.core.api.v1.mapper

import com.github.melancholic.fintrace.core.api.v1.dto.CategoryResponse
import com.github.melancholic.fintrace.core.domain.projection.CategoryProjection
import org.mapstruct.Mapper

@Mapper(componentModel = "spring")
interface CategoryMapper {
    fun toResponse(projection: CategoryProjection): CategoryResponse
    fun toResponseList(projections: List<CategoryProjection>): List<CategoryResponse>
}