package com.github.melancholic.fintrace.core.validation

import com.github.melancholic.fintrace.core.domain.command.CreateOperationCommand
import org.springframework.stereotype.Service

interface OperationValidationService {
    fun validate(operation: CreateOperationCommand) {}
}

@Service
class OperationValidationServiceImpl : OperationValidationService {

}