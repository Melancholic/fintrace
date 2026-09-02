package com.github.melancholic.fintrace.core.db

import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the naming convention rather than the SQL, so it needs no database.
 */
class MigrationNamingTest {

	private val migrations = PathMatchingResourcePatternResolver()
		.getResources("classpath*:db/migration/*.sql")
		.mapNotNull { it.filename }

	@Test
	fun `every migration uses a four-digit version`() {
		assertTrue(migrations.isNotEmpty(), "no migrations found on the classpath")

		// Flyway compares versions numerically, so V1, V01 and V0001 are all version 1. Mixing
		// widths fails at startup with "Found more than one migration with version 1".
		val malformed = migrations.filterNot { it.matches(NAMING) }
		assertEquals(emptyList(), malformed, "migrations not matching V0000__name.sql")
	}

	@Test
	fun `versions are unique`() {
		val versions = migrations.map { it.substring(1, 5) }
		val duplicated = versions.groupBy { it }.filterValues { it.size > 1 }.keys

		assertEquals(emptySet(), duplicated)
	}

	private companion object {
		val NAMING = Regex("""V\d{4}__[a-z0-9_]+\.sql""")
	}
}
