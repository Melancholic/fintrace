package com.github.melancholic.fintrace.core.api.v1.mapper

import com.github.melancholic.fintrace.core.api.v1.dto.OperationResponse
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.Named
import java.math.BigDecimal

@Mapper(componentModel = "spring")
interface OperationMapper {

	@Mapping(source = "amount", target = "amount", qualifiedByName = ["signedToUnsignedAmount"])
	fun toResponse(projection: OperationProjection): OperationResponse

	@Mapping(source = "amount", target = "amount", qualifiedByName = ["signedToUnsignedAmount"])
	fun toResponseList(projections: List<OperationProjection>): List<OperationResponse>

	@Named("signedToUnsignedAmount")
	fun signedToUnsignedAmount(amount: BigDecimal): BigDecimal = amount.abs()
}
