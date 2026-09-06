package com.github.melancholic.fintrace.core.validation

import com.github.melancholic.fintrace.core.api.v1.dto.CreateWorkspaceRequest
import com.github.melancholic.fintrace.core.api.v1.dto.EditWorkspaceRequest
import com.github.melancholic.fintrace.core.exception.ValidationError
import com.github.melancholic.fintrace.core.validation.ValidationConstants.MAX_NAME_LENGTH
import com.github.melancholic.fintrace.core.validation.ValidationConstants.WORKSPACE_NAME_PATTERN
import org.springframework.stereotype.Service

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

    private fun checkCurrency(code: String) = CurrencyValidator.requireKnown(code)

    companion object {
        private val NAME_REGEX = Regex(WORKSPACE_NAME_PATTERN)
    }
}
