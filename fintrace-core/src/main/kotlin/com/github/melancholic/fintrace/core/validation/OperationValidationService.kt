package com.github.melancholic.fintrace.core.validation

import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.command.*
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.exception.ValidationError
import com.github.melancholic.fintrace.core.util.TimestampProvider
import org.springframework.stereotype.Service

interface OperationValidationService {
    fun validate(operation: CreateOperationCommand)
    fun validate(operation: ReviseOperationCommand)
    fun validate(operation: CancelOperationCommand)
}

@Service
class OperationValidationServiceImpl(
    private val projectionDAO: OperationProjectionDAO,
    private val timestampProvider: TimestampProvider
) : OperationValidationService {
    override fun validate(operation: CreateOperationCommand) {
        checkOccurredAt(operation)
    }

    override fun validate(operation: ReviseOperationCommand) {
        checkExist(operation)
        checkOccurredAt(operation)
    }

    override fun validate(operation: CancelOperationCommand) {
        checkExist(operation)
        checkOccurredAt(operation)
    }


    private fun checkOccurredAt(operation: OperationCommand<*>) {
        if (timestampProvider.now().isBefore(operation.occurredAt)) {
            throw ValidationError("`occuredAt` has an incorrect value")
        }
    }

    private fun checkExist(operation: ExistingOperationCommand<*>) {
        if (!projectionDAO.exists(operation.workspaceId, operation.operationId)) {
            throw NotFoundEntityException("Operation projection with id='${operation.operationId}' not found")
        }
    }

}