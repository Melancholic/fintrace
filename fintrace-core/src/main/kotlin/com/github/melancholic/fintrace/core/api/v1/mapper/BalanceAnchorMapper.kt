package com.github.melancholic.fintrace.core.api.v1.mapper

import com.github.melancholic.fintrace.core.api.v1.dto.BalanceAnchorResponse
import com.github.melancholic.fintrace.core.domain.projection.BalanceAnchorProjection
import org.mapstruct.Mapper

@Mapper(componentModel = "spring")
interface BalanceAnchorMapper {
    fun toResponse(projection: BalanceAnchorProjection): BalanceAnchorResponse
    fun toResponseList(projections: List<BalanceAnchorProjection>): List<BalanceAnchorResponse>
}