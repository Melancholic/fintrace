package com.github.melancholic.fintrace.core.validation

import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.CategoryProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.command.*
import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.exception.ActionConflictException
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.exception.OperationNotSupported
import com.github.melancholic.fintrace.core.exception.ValidationError
import com.github.melancholic.fintrace.core.util.TimestampProvider
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.*

interface OperationValidationService {
    fun validate(command: CreateOperationCommand)
    fun validate(command: ReviseOperationCommand)
    fun validate(command: CancelOperationCommand)
}

@Service
class OperationValidationServiceImpl(
    private val projectionDAO: OperationProjectionDAO,
    private val timestampProvider: TimestampProvider,
    private val accountDAO: AccountProjectionDAO,
    private val categoryDAO: CategoryProjectionDAO
) : OperationValidationService {
    override fun validate(command: CreateOperationCommand) {
        checkOccurredAt(command)
        checkKind(command)
        checkAmount(command)
        checkAccountOnCreate(command)
        checkCategoryOnCreate(command)
    }

    override fun validate(command: ReviseOperationCommand) {
        checkTransfer(command.workspaceId, command.operationId)
        checkOccurredAt(command)
        checkKind(command)
        checkAmount(command)
        checkAccountOnRevise(command)
        checkCategoryOnRevise(command)
    }

    override fun validate(command: CancelOperationCommand) {
        checkTransfer(command.workspaceId, command.operationId)
    }

    private fun checkKind(operation: OperationStateCommand<*>) {
        if (operation.kind == OperationKind.TRANSFER) {
            throw TransferOperationNotSupportedException()
        }
    }

    private fun checkOccurredAt(command: TemporalCommand<*>) {
        if (timestampProvider.now().isBefore(command.occurredAt)) {
            throw ValidationError("`occuredAt` has an incorrect value")
        }
    }

    private fun requireExists(workspaceId: UUID, operationId: UUID): OperationProjection =
        projectionDAO.getByIdAsOptional(workspaceId, operationId)
            .orElseThrow { NotFoundOperationException(operationId) }

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

    private fun checkTransfer(workspaceId: UUID, operationId: UUID) {
        val operation = requireExists(workspaceId, operationId)
        if (operation.isTransfer()) {
            throw TransferOperationNotSupportedException(
                "Operation belongs to transfer id='${operation.transferId}' and couldn't be changed on its own"
            )
        }
    }

    class TransferOperationNotSupportedException(message: String = "Transfer couldn't be processed as an ordinary operation") :
        OperationNotSupported(message)

    class NotFoundOperationException(operationId: UUID) :
        NotFoundEntityException("Operation with id='${operationId}' not found")
}