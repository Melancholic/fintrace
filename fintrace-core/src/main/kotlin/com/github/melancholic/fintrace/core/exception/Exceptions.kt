package com.github.melancholic.fintrace.core.exception

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus
import java.util.*

// BUSINESS EXCEPTIONS
@ResponseStatus(value = HttpStatus.NOT_FOUND)
open class NotFoundEntityException(message: String = "Entity not found") : RuntimeException(message)

@ResponseStatus(value = HttpStatus.BAD_REQUEST)
class ValidationError(message: String = "Validation error")  : RuntimeException(message)

@ResponseStatus(value = HttpStatus.CONFLICT)
open class ActionConflictException(message: String = "Conflict") : RuntimeException(message)

@ResponseStatus(value = HttpStatus.CONFLICT)
class OperationNotAllowedException(message: String = "Operation not allowed") : ActionConflictException(message)

@ResponseStatus(value = HttpStatus.CONFLICT)
class OperationNotSupported(message: String = "Operation not supported") : ActionConflictException(message)

// SECURITY EXCEPTIONS
@ResponseStatus(value = HttpStatus.UNAUTHORIZED)
class NotAuthenticatedException : RuntimeException("Not authenticated")

@ResponseStatus(value = HttpStatus.FORBIDDEN)
class WorkspaceNotPermitted(userId: String, workspaceId: UUID) :
    RuntimeException("User $userId not permitted to workspace '$workspaceId'")

// SYSTEM ERRORS
@ResponseStatus(value = HttpStatus.INTERNAL_SERVER_ERROR)
class ApplicationException(message: String = "Server error")  : RuntimeException(message)