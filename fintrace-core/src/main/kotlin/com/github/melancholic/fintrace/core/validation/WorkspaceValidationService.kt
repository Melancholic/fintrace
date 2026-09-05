package com.github.melancholic.fintrace.core.validation

import com.github.melancholic.fintrace.core.api.v1.dto.CreateWorkspaceRequest
import com.github.melancholic.fintrace.core.api.v1.dto.EditWorkspaceRequest
import com.github.melancholic.fintrace.core.exception.ValidationError
import org.springframework.stereotype.Service
import java.util.*

interface WorkspaceValidationService {
    fun validate(request: CreateWorkspaceRequest)
    fun validate(request: EditWorkspaceRequest)
}

@Service
class WorkspaceValidationServiceImpl : WorkspaceValidationService {

    override fun validate(request: CreateWorkspaceRequest) {
        checkName(request.workspaceName)
        checkCurrency(request.defaultCurrency)
    }

    override fun validate(request: EditWorkspaceRequest) {
        request.workspaceName?.let { checkName(it) }
        request.defaultCurrency?.let { checkCurrency(it) }

        if (request.version < 0) {
            throw ValidationError("Workspace version must not be negative, was ${request.version}")
        }
    }

    private fun checkName(name: String) {
        if (name.isBlank()) {
            throw ValidationError("Workspace name must not be blank")
        }
        if (name.length > MAX_NAME_LENGTH) {
            throw ValidationError("Workspace name must be at most $MAX_NAME_LENGTH characters, was ${name.length}")
        }
        if (!NAME_REGEX.matches(name)) {
            throw ValidationError("Workspace name '$name' contains characters that are not permitted")
        }
    }

    private fun checkCurrency(code: String) {
        if (!CURRENCY_REGEX.matches(code)) {
            throw ValidationError("Currency must be a three-letter uppercase ISO-4217 code, was '$code'")
        }

        // JDK-based currency validator
        runCatching { Currency.getInstance(code) }
            .onFailure { throw ValidationError("Unknown currency code '$code'") }
    }

    companion object {
        const val MAX_NAME_LENGTH = 30
        const val WORKSPACE_NAME_PATTERN = "[a-zA-Z0-9][a-zA-Z0-9\\-_\\[\\]()]*"
        const val CURRENCY_PATTERN = "[A-Z]{3}"

        private val NAME_REGEX = Regex(WORKSPACE_NAME_PATTERN)
        private val CURRENCY_REGEX = Regex(CURRENCY_PATTERN)
    }
}
