package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.dao.UsersDAO
import com.github.melancholic.fintrace.core.dao.WorkspaceDAO
import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.CategoryProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.command.CreateOperationCommand
import com.github.melancholic.fintrace.core.domain.entity.OperationKind
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
import org.springframework.security.test.context.support.WithMockUser
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
@WithMockUser(username = TestWorkspaces.TEST_SUBJECT)
class CommandFacadeTransactionTest(
	@Autowired private val facade: CommandFacade,
	@Autowired private val jdbc: JdbcClient,
	@Autowired private val workspaceDAO: WorkspaceDAO,
	@Autowired private val usersDAO: UsersDAO,
	@Autowired private val accountDAO: AccountProjectionDAO,
	@Autowired private val categoryDAO: CategoryProjectionDAO,
) {

	private lateinit var workspaceId: UUID
	private lateinit var accountId: UUID
	private lateinit var categoryId: UUID

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

			override fun getByIdAsOptional(workspaceId: UUID, operationId: UUID): Optional<OperationProjection> =
				throw UnsupportedOperationException()

			override fun removeAll(workspaceId: UUID) = throw UnsupportedOperationException()

			override fun remove(workspaceId: UUID, id: UUID) = throw UnsupportedOperationException()

			override fun remove(workspaceId: UUID, ids: Set<UUID>) = throw UnsupportedOperationException()
		}
	}

	@BeforeEach
	fun clean() {
		TestWorkspaces.reset(jdbc)
		workspaceId = TestWorkspaces.create(workspaceDAO, usersDAO)
		accountId = TestWorkspaces.seedAccount(accountDAO, workspaceId)
		categoryId = TestWorkspaces.seedCategory(categoryDAO, workspaceId)
	}

	@Test
	fun `rolls the event back when the projection write fails`() {
		assertFailsWith<ProjectionFailed> {
			facade.processCommand(
				CreateOperationCommand(
					workspaceId = workspaceId,
					occurredAt = LocalDateTime.parse("2026-03-15T14:30:00"),
					amount = BigDecimal("100.0000"),
					accountId = accountId,
					kind = OperationKind.EXPENSE,
					categoryId = categoryId,
					comment = null,
				)
			)
		}

		// The event insert already succeeded before the failure, so a surviving row here would
		// mean the two writes were not in one transaction.
		assertEquals(0, jdbc.sql("SELECT count(*) FROM t_events").query(Int::class.java).single())
		assertEquals(0, jdbc.sql("SELECT count(*) FROM t_operations").query(Int::class.java).single())
	}
}
