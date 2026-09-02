package com.github.melancholic.fintrace.core.api.v1.mapper

import com.github.melancholic.fintrace.core.api.v1.dto.OperationResponse
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import org.mapstruct.Mapper

/**
 * Projection row → API response.
 *
 * `workspaceId` is deliberately absent from the response: it is already in the request path,
 * and every target property is mapped by name, so the generated implementation needs no
 * explicit `@Mapping`.
 */
@Mapper(componentModel = "spring")
interface OperationMapper {
	fun toResponse(projection: OperationProjection): OperationResponse
	fun toResponseList(projections: List<OperationProjection>): List<OperationResponse>
}
