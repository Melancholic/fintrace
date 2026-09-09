package com.github.melancholic.fintrace.core.validation

import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.CategoryProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.command.*
import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import com.github.melancholic.fintrace.core.exception.ActionConflictException
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.exception.OperationNotSupported
import com.github.melancholic.fintrace.core.exception.ValidationError
import com.github.melancholic.fintrace.core.util.TimestampProvider
import org.springframework.stereotype.Service
import java.math.BigDecimal

interface OperationValidationService {
    fun validate(operation: CreateOperationCommand)
    fun validate(operation: ReviseOperationCommand)
    fun validate(operation: CancelOperationCommand)
}

@Service
class OperationValidationServiceImpl(
    private val projectionDAO: OperationProjectionDAO,
    private val timestampProvider: TimestampProvider,
    private val accountDAO: AccountProjectionDAO,
    private val categoryDAO: CategoryProjectionDAO
) : OperationValidationService {
    override fun validate(operation: CreateOperationCommand) {
        checkOccurredAt(operation)
        checkKind(operation)
        checkAmount(operation)
        checkAccountOnCreate(operation)
        checkCategoryOnCreate(operation)
    }

    override fun validate(operation: ReviseOperationCommand) {
        checkExist(operation)
        checkOccurredAt(operation)
        checkAmount(operation)
        checkAccountOnRevise(operation)
        checkCategoryOnRevise(operation)
    }

    override fun validate(operation: CancelOperationCommand) {
        checkExist(operation)
    }

    private fun checkKind(operation: OperationStateCommand<*>) {
        if (operation.kind == OperationKind.TRANSFER) {
            throw OperationNotSupported("Transfer operation not allowed in this endpoint")
        }
    }

    private fun checkOccurredAt(command: TemporalCommand<*>) {
        if (timestampProvider.now().isBefore(command.occurredAt)) {
            throw ValidationError("`occuredAt` has an incorrect value")
        }
    }

    private fun checkExist(command: ExistingOperationCommand<*>) {
        if (!projectionDAO.exists(command.workspaceId, command.operationId)) {
            throw NotFoundEntityException("Operation projection with id='${command.operationId}' not found")
        }
    }

    private fun checkAmount(command: OperationStateCommand<*>) {
        if (command.amount <= BigDecimal.ZERO) {
            throw ValidationError("`amount` should be positive")
        }
    }

    private fun checkAccountOnCreate(command: CreateOperationCommand) {
        val account = accountDAO.getById(command.workspaceId, command.accountId)
        if (account.archived) {
            throw ActionConflictException("Couldn't create new operation under archived account")
        }
    }

    private fun checkAccountOnRevise(command: ReviseOperationCommand) {
        accountDAO.getById(command.workspaceId, command.accountId)
    }

    private fun checkCategoryOnCreate(command: CreateOperationCommand) {
        if (command.categoryId == null) {
            return
        }
        val category = categoryDAO.getById(command.workspaceId, command.categoryId)

        if (category.archived) {
            throw ActionConflictException("Couldn't create new operation under archived category")
        }

        if (category.kind != command.kind.asCategoryKind()) {
            throw ActionConflictException("Operation with kind=`${command.kind}` couldn't be under a category with kind='${category.kind}'")
        }
    }

    private fun checkCategoryOnRevise(command: ReviseOperationCommand) {
        val category = categoryDAO.getById(command.workspaceId, command.categoryId)

        if (category.kind != command.kind.asCategoryKind()) {
            throw ActionConflictException("Operation with kind=`${command.kind}` couldn't be under a category with kind='${category.kind}'")
        }
    }
}