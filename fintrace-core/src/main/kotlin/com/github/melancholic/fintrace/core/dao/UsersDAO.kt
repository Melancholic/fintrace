package com.github.melancholic.fintrace.core.dao

import com.github.melancholic.fintrace.core.dao.mapper.UserRowMapper
import com.github.melancholic.fintrace.core.domain.entity.User
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.*

interface UsersDAO {
    fun findUserByUserName(username: String): Optional<User>
    fun getUserIdByExternalId(externalId: String): Optional<UUID>
}

@Repository
class UsersDAOImpl(
    private val jdbc: JdbcClient,
    private val userRowMapper: UserRowMapper
) : UsersDAO {

    override fun findUserByUserName(username: String): Optional<User> = jdbc.sql(SELECT_BY_USERNAME)
        .param("username", username)
        .query(userRowMapper)
        .optional()

    override fun getUserIdByExternalId(externalId: String): Optional<UUID> = jdbc.sql(SELECT_ID_BY_EXT_ID)
        .param("externalId", externalId)
        .query(UUID::class.java)
        .optional()

    private companion object {
        const val SELECT_BY_USERNAME = "select * from t_users where username = :username"
        const val SELECT_ID_BY_EXT_ID = "select id from t_users where external_id = :externalId"
    }
}