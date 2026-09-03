package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.command.CreateOperationCommand
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The event and the projection update must share one transaction (§4.10) — otherwise an event
 * can be recorded whose projection was never written, and the two silently diverge.
 *
 * Isolated in its own class because the projection DAO is replaced by one that always fails.
 */
@Import(TestcontainersConfiguration::class, CommandFacadeTransactionTest.FailingProjection::class)
@SpringBootTest
class CommandFacadeTransactionTest(
	@Autowired private val facade: CommandFacade,
	@Autowired private val jdbc: JdbcClient,
) {

	class ProjectionFailed : RuntimeException("projection write failed")

	@TestConfiguration
	class FailingProjection {
		@Bean
		@Primary
		fun failingOperationProjectionDAO() = object : OperationProjectionDAO {
			override fun createOrUpdate(projection: OperationProjection): UUID = throw ProjectionFailed()

			// Unused here — this class only exercises the write path's rollback.
			override fun getById(workspaceId: UUID, operationId: UUID): OperationProjection =
				throw UnsupportedOperationException()

			override fun removeAll(workspaceId: UUID) = throw UnsupportedOperationException()

			override fun remove(workspaceId: UUID, id: UUID) = throw UnsupportedOperationException()

			override fun exists(workspaceId: UUID, operationId: UUID): Boolean =
				throw UnsupportedOperationException()
		}
	}

	@BeforeEach
	fun clean() {
		jdbc.sql("DELETE FROM t_operations").update()
		jdbc.sql("DELETE FROM t_events").update()
	}

	@Test
	fun `rolls the event back when the projection write fails`() {
		assertFailsWith<ProjectionFailed> {
			facade.processCommand(
				CreateOperationCommand(
					UUID.fromString("0199a1c2-3d4e-7f80-8123-000000000001"),
					LocalDateTime.parse("2026-03-15T14:30:00"),
					BigDecimal("100.0000"),
				)
			)
		}

		// The event insert already succeeded before the failure, so a surviving row here would
		// mean the two writes were not in one transaction.
		assertEquals(0, jdbc.sql("SELECT count(*) FROM t_events").query(Int::class.java).single())
		assertEquals(0, jdbc.sql("SELECT count(*) FROM t_operations").query(Int::class.java).single())
	}
}
