package com.github.melancholic.fintrace.core.dao

import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.dao.projection.ProjectionDAORegistry
import org.junit.jupiter.api.Test
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 1.6: `workspace_id` on every query, checked mechanically.
 *
 * The mechanism chosen is an explicit parameter rather than a repository-level guard — nothing in
 * `JdbcClient` can enforce scoping for us, so the guard is this test. It reads the SQL constants
 * off the DAOs by reflection instead of naming them one by one, so a DAO added later is covered
 * the day it becomes a bean.
 *
 * What it protects: a query that forgets the workspace reads or writes another tenant's rows and
 * returns a perfectly plausible answer. Nothing else in the suite fails when that happens.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class WorkspaceScopingTest(
    @Autowired private val registry: ProjectionDAORegistry,
    @Autowired private val eventsDAO: EventsDAO,
    @Autowired private val jdbc: JdbcClient,
) {

    @Test
    fun `every projection DAO scopes all of its SQL by workspace`() {
        val daos = registry.asList()
        assertTrue(daos.isNotEmpty(), "no projection DAOs found — the registry cannot be empty")

        val unscoped = daos.flatMap { unscopedStatementsOf(it) }

        assertEquals(emptyList(), unscoped, "SQL that does not mention workspace_id")
    }

    @Test
    fun `the event store scopes its SQL by workspace too`() {
        // t_events carries workspace_id like every projection table, and replay reads one
        // workspace's log — a missing scope here would replay someone else's history.
        assertEquals(emptyList(), unscopedStatementsOf(eventsDAO))
    }

    @Test
    fun `every table that carries workspace_id has it NOT NULL`() {
        // The other half of the guarantee: scoping a query is pointless if a row can be written
        // without a workspace in the first place.
        val nullable = jdbc.sql(
            """
			SELECT table_name FROM information_schema.columns
			WHERE column_name = 'workspace_id' AND is_nullable = 'YES'
			  AND table_schema = 'public'
			ORDER BY table_name
			"""
        ).query(String::class.java).list()

        assertEquals(emptyList(), nullable)
    }

    @Test
    fun `the guard can actually fail`() {
        // A test that reads fields by reflection is worth nothing if it silently finds none — a
        // renamed constant or an inlined string would make the two tests above vacuous.
        assertTrue(
            registry.asList().sumOf { sqlOf(it).size } > 10,
            "found almost no SQL constants; the reflection below has stopped matching",
        )
        assertEquals(listOf("SELECT * FROM t_operations"), unscopedIn(listOf("SELECT * FROM t_operations")))
    }

    private fun unscopedStatementsOf(dao: Any): List<String> = unscopedIn(sqlOf(dao))

    private fun unscopedIn(statements: List<String>) =
        statements.filterNot { it.contains("workspace_id", ignoreCase = true) }

    /**
     * `const val` in a companion compiles to a static field on the containing class, private
     * companion included — hence the accessibility override. The class has to come from
     * `AopUtils`: a `@Repository` is a CGLIB proxy, and the proxy declares none of these fields.
     */
    private fun sqlOf(dao: Any): List<String> = AopUtils.getTargetClass(dao).declaredFields
        .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .mapNotNull { field ->
            field.isAccessible = true
            field.get(null) as? String
        }
        .filter { value -> KEYWORDS.any { value.contains(it, ignoreCase = true) } }

    private companion object {
        // TABLE_NAME and similar constants are strings too; only statements are of interest.
        val KEYWORDS = listOf("SELECT ", "INSERT ", "UPDATE ", "DELETE ")
    }
}
