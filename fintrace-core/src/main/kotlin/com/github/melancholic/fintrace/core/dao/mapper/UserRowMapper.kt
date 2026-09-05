package com.github.melancholic.fintrace.core.dao.mapper

import com.github.melancholic.fintrace.core.domain.entity.User
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.LocalDateTime
import java.util.*

@Component
class UserRowMapper : RowMapper<User> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int
    ): User = User(
        id = rs.getObject("id", UUID::class.java),
        userName = rs.getString("username"),
        externalId = rs.getString("external_id"),
        createdAt = rs.getObject("created_at", LocalDateTime::class.java),
    )
}