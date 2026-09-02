package com.github.melancholic.fintrace.core.security

import com.github.melancholic.fintrace.core.exception.NotAuthenticatedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.stereotype.Component


interface IdentityProvider {
    fun currentUser(): UserDetails?
    fun currentUserName(): String

}

@Component
class IdentityProviderImpl : IdentityProvider {

    override fun currentUser() = SecurityContextHolder.getContext()
        .authentication
        ?.principal as? UserDetails
        ?: throw NotAuthenticatedException()

    override fun currentUserName(): String = currentUser().username

}