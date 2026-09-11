package com.github.melancholic.fintrace.core.validation

import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.BalanceAnchorProjectionDAO
import com.github.melancholic.fintrace.core.domain.command.BalanceAnchorCommand
import com.github.melancholic.fintrace.core.domain.command.CancelBalanceAnchorCommand
import com.github.melancholic.fintrace.core.domain.command.CreateBalanceAnchorCommand
import com.github.melancholic.fintrace.core.exception.ActionConflictException
import org.springframework.stereotype.Service

interface BalanceAnchorValidationService {
    fun validate(command: CreateBalanceAnchorCommand)
    fun validate(command: CancelBalanceAnchorCommand)
}

@Service
class BalanceAnchorValidationServiceImpl(
    private val projectionDAO: BalanceAnchorProjectionDAO,
    private val accountDAO: AccountProjectionDAO
) : BalanceAnchorValidationService {
    override fun validate(command: CreateBalanceAnchorCommand) {
        checkAccount(command)
    }

    override fun validate(command: CancelBalanceAnchorCommand) {
        checkAccount(command)
        checkAnchorId(command)
    }

    private fun checkAnchorId(command: CancelBalanceAnchorCommand) {
        val requested = projectionDAO.getById(command.workspaceId, command.accountId, command.anchorId)
        if (!projectionDAO.isLast(requested)) {
            throw ActionConflictException("Couldn't remove balance anchor: only last anchor may be deleted.")
        }
    }

    private fun checkAccount(command: BalanceAnchorCommand<*>) {
        val account = accountDAO.getById(command.workspaceId, command.accountId)
        if (account.archived) {
            throw ActionConflictException("Couldn't manage balance anchor: account shouldn't be archived")
        }
    }

}