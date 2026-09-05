package com.github.melancholic.fintrace.core.security


import com.github.melancholic.fintrace.core.exception.NotAuthenticatedException
import com.github.melancholic.fintrace.core.service.UserService
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.stereotype.Component
import java.util.*


interface IdentityProvider {
    fun currentUser(): UserDetails
    fun currentUserName(): String

    fun currentSubject(): String
    fun currentUserId(): UUID
}

@Component
open class IdentityProviderImpl(
    val userService: UserService,
) : IdentityProvider {
    override fun currentUser() = SecurityContextHolder.getContext()
        .authentication
        ?.principal as? UserDetails
        ?: throw NotAuthenticatedException()

    override fun currentUserName(): String = currentUser().username

    // TODO: Should be taken from JWT later
    // M5: an authenticated subject with no local row becomes just-in-time provisioning
    // rather than a rejection.
    override fun currentSubject(): String = SecurityContextHolder.getContext().authentication
        ?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
        ?.name
        ?: throw NotAuthenticatedException()


    override fun currentUserId(): UUID = userService.getUserIdBySub(currentSubject())
        ?: throw NotAuthenticatedException()

}