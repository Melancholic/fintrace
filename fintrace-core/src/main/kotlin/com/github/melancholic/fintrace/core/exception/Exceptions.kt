package com.github.melancholic.fintrace.core.exception

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus
import java.util.*

// BUSINESS EXCEPTIONS
@ResponseStatus(value = HttpStatus.NOT_FOUND)
class NotFoundEntityException(message: String = "Entity not found") : RuntimeException(message)

@ResponseStatus(value = HttpStatus.BAD_REQUEST)
class ValidationError(message: String = "Validation error")  : RuntimeException(message)


// SECURITY EXCEPTIONS
@ResponseStatus(value = HttpStatus.UNAUTHORIZED)
class NotAuthenticatedException : RuntimeException("Not authenticated")

@ResponseStatus(value = HttpStatus.FORBIDDEN)
class WorkspaceNotPermitted(userId: String, workspaceId: UUID) :
    RuntimeException("User $userId not permitted to workspace '$workspaceId'")