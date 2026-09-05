package com.github.melancholic.fintrace.core.service

import com.github.melancholic.fintrace.core.config.CacheConfiguration.Companion.USERID_BY_SUB_CACHE
import com.github.melancholic.fintrace.core.dao.UsersDAO
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.util.*

interface UserService {
    fun getUserIdBySub(sub: String): UUID?
}

@Service
class UserServiceImpl(
    val usersDAO: UsersDAO
) : UserService {

    @Cacheable(USERID_BY_SUB_CACHE, sync = true)
    override fun getUserIdBySub(sub: String): UUID? = usersDAO.getUserIdByExternalId(sub)
        .orElseGet { null }

}