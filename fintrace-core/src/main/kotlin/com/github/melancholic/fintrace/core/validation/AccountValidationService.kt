package com.github.melancholic.fintrace.core.validation

import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.domain.command.CreateAccountCommand
import com.github.melancholic.fintrace.core.domain.command.ExistingAccountCommand
import com.github.melancholic.fintrace.core.domain.command.ReviseAccountCommand
import com.github.melancholic.fintrace.core.domain.command.SetAccountArchivedCommand
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.exception.ValidationError
import com.github.melancholic.fintrace.core.validation.ValidationConstants.ACCOUNT_NAME_PATTERN
import com.github.melancholic.fintrace.core.validation.ValidationConstants.MAX_ICON_LENGTH
import com.github.melancholic.fintrace.core.validation.ValidationConstants.MAX_NAME_LENGTH
import org.springframework.stereotype.Service

interface AccountValidationService {
    fun validate(account: CreateAccountCommand)
    fun validate(account: ReviseAccountCommand)
    fun validate(account: SetAccountArchivedCommand)
}

@Service
class AccountValidationServiceImpl(
    private val accountDAO: AccountProjectionDAO
) : AccountValidationService {

    override fun validate(account: CreateAccountCommand) {
        checkName(account.name)
        checkIcon(account.icon)
        CurrencyValidator.requireKnown(account.currency)
    }

    override fun validate(account: ReviseAccountCommand) {
        checkExists(account)
        checkName(account.name)
        checkIcon(account.icon)
    }

    override fun validate(account: SetAccountArchivedCommand) {
        checkExists(account)
    }

    private fun checkExists(account: ExistingAccountCommand<*>) {
        if (!accountDAO.exists(account.workspaceId, account.accountId)) {
            throw NotFoundEntityException("Account not found (workspaceId='${account.workspaceId}', accountId='${account.accountId}')")
        }
    }

    private fun checkName(name: String) {
        if (name.isBlank()) {
            throw ValidationError("Account name must not be blank")
        }
        if (name.length > MAX_NAME_LENGTH) {
            throw ValidationError("Account name must be at most $MAX_NAME_LENGTH characters, was ${name.length}")
        }
        if (!NAME_REGEX.matches(name)) {
            throw ValidationError("Account name '$name' contains characters that are not permitted")
        }
    }

    private fun checkIcon(icon: String?) {
        if (icon != null && icon.length > MAX_ICON_LENGTH) {
            throw ValidationError("Account icon must be at most $MAX_ICON_LENGTH characters, was ${icon.length}")
        }
    }

    private companion object {
        val NAME_REGEX = Regex(ACCOUNT_NAME_PATTERN)
    }
}
