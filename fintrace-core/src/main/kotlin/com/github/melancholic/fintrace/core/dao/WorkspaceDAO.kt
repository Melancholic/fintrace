package com.github.melancholic.fintrace.core.dao

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

interface WorkspaceDAO {
}

@Repository
class WorkspaceDAOImpl(
    private val jdbc: JdbcClient
) : WorkspaceDAO {

}