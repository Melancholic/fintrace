package com.github.melancholic.fintrace.core.api.v1.mapper

import com.github.melancholic.fintrace.core.api.v1.dto.AccountResponse
import com.github.melancholic.fintrace.core.domain.projection.AccountProjection
import org.mapstruct.Mapper

@Mapper(componentModel = "spring")
interface AccountMapper {
    fun toResponse(projection: AccountProjection): AccountResponse
    fun toResponseList(projections: List<AccountProjection>): List<AccountResponse>
}