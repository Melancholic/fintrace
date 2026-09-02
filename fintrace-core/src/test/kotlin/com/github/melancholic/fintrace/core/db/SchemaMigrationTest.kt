package com.github.melancholic.fintrace.core.db

import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Flyway runs during context startup, so a migration that fails to apply fails this class
 * outright. The assertions below cover what applying successfully does *not* catch: the schema
 * decisions that a later migration could silently reverse.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class SchemaMigrationTest(@Autowired private val jdbc: JdbcClient) {

	@Test
	fun `all migrations applied, in version order`() {
		val applied = jdbc.sql(
			"SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank"
		).query(String::class.java).list().filterNotNull()

		assertTrue(applied.containsAll(listOf("0001", "0002", "0003")), "applied: $applied")
		assertEquals(applied.sorted(), applied, "migrations applied out of version order")

		val failed = jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE NOT success")
			.query(Int::class.java).single()
		assertEquals(0, failed)
	}

	@Test
	fun `temporal columns carry no timezone`() {
		val columns = listOf(
			"t_events" to "occurred_at",
			"t_events" to "recorded_at",
			"t_operations" to "occurred_at",
			"t_operations" to "recorded_at",
		)
		// §6.4: timestamps are stored in the system timezone with no zone attached. timestamptz
		// would re-anchor every stored value to the reader's offset.
		columns.forEach { (table, column) ->
			assertEquals("timestamp without time zone", dataType(table, column), "$table.$column")
		}
	}

	@Test
	fun `money is exact, not floating point`() {
		assertEquals("numeric", dataType("t_operations", "amount"))

		val precisionAndScale = jdbc.sql(
			"""
			SELECT numeric_precision || ',' || numeric_scale
			FROM information_schema.columns
			WHERE table_name = 't_operations' AND column_name = 'amount'
			"""
		).query(String::class.java).single()
		assertEquals("19,4", precisionAndScale)
	}

	@Test
	fun `projection columns are never generated at write time`() {
		// Every value comes from the event, so a rebuild reproduces the row exactly. A serial
		// or DEFAULT now() here would make the rebuild-equality test (0.12) unable to compare.
		val generated = jdbc.sql(
			"""
			SELECT column_name FROM information_schema.columns
			WHERE table_name = 't_operations'
			  AND (column_default IS NOT NULL OR is_identity = 'YES')
			"""
		).query(String::class.java).list()

		assertEquals(emptyList(), generated, "projection columns with a generated value")
	}

	@Test
	fun `operations is keyed by the aggregate id`() {
		val primaryKey = jdbc.sql(
			"""
			SELECT a.attname
			FROM pg_index i
			JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)
			WHERE i.indrelid = 't_operations'::regclass AND i.indisprimary
			"""
		).query(String::class.java).list()

		assertEquals(listOf("id"), primaryKey)
		assertEquals("uuid", dataType("t_operations", "id"))
	}

	@Test
	fun `the event sequence is assigned by the database alone`() {
		// GENERATED ALWAYS: an INSERT supplying id is rejected outright. events.id is the global
		// order a rebuild replays by, and nothing in the application may set it.
		val identity = jdbc.sql(
			"""
			SELECT is_identity || ',' || identity_generation
			FROM information_schema.columns
			WHERE table_name = 't_events' AND column_name = 'id'
			"""
		).query(String::class.java).single()

		assertEquals("YES,ALWAYS", identity)
	}

	@Test
	fun `events carries the index a rebuild replays through`() {
		// §4.14: a full rebuild reads one workspace's events in id order.
		val definition = jdbc.sql(
			"SELECT indexdef FROM pg_indexes WHERE tablename = 't_events' AND indexname = :name"
		).param("name", "idx_t_events_workspace_id_id").query(String::class.java).optional()

		assertTrue(definition.isPresent, "idx_t_events_workspace_id_id is missing")
		assertTrue(
			definition.get().contains("(workspace_id, id)"),
			"unexpected index definition: ${definition.get()}",
		)
	}

	private fun dataType(table: String, column: String): String =
		requireNotNull(
			jdbc.sql(
				"""
				SELECT data_type FROM information_schema.columns
				WHERE table_name = :table AND column_name = :column
				"""
			).param("table", table).param("column", column).query(String::class.java).single()
		) { "no such column: $table.$column" }
}
