package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.domain.command.CreateOperationCommand
import com.github.melancholic.fintrace.core.facade.CommandFacade
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals

/** HTTP contract for the replay endpoint. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminRestControllerTest(
	@Autowired private val mvc: MockMvc,
	@Autowired private val commandFacade: CommandFacade,
	@Autowired private val jdbc: JdbcClient,
) {

	@BeforeEach
	fun clean() {
		jdbc.sql("DELETE FROM t_operations").update()
		jdbc.sql("DELETE FROM t_events").update()
	}

	@Test
	fun `replays a workspace`() {
		repeat(3) { create() }
		jdbc.sql("DELETE FROM t_operations").update()

		mvc.perform(post(REPLAY_PATH).with(user("admin").roles(ADMIN_ROLE)).with(csrf()))
			.andExpect(status().isOk)

		assertEquals(3, count(), "the projection is rebuilt from the event log")
	}

	@Test
	fun `replaying an unknown workspace succeeds and writes nothing`() {
		mvc.perform(
			post("/admin/api/v1/workspaces/${UUID.randomUUID()}/replay").with(user("admin").roles(ADMIN_ROLE)).with(csrf())
		).andExpect(status().isOk)

		assertEquals(0, count())
	}

	@Test
	fun `rejects a replay by a non-admin`() {
		repeat(2) { create() }

		// Authenticated is not enough: replay wipes and rebuilds a whole workspace, so it is
		// gated on the role rather than merely on being signed in.
		mvc.perform(post(REPLAY_PATH).with(user("regular").roles("USER")).with(csrf()))
			.andExpect(status().isForbidden)

		assertEquals(2, count(), "a non-admin cannot clear the projection")
	}

	@Test
	fun `rejects an unauthenticated replay`() {
		repeat(2) { create() }

		mvc.perform(post(REPLAY_PATH)).andExpect(status().isForbidden)

		// The projection must be untouched — an unauthenticated caller cannot even clear it.
		assertEquals(2, count())
	}

	private fun create() = commandFacade.processCommand(
		CreateOperationCommand(
			WORKSPACE,
			LocalDateTime.parse("2026-03-15T14:30:00"),
			BigDecimal("100.0000"),
		)
	)

	private fun count() = jdbc.sql("SELECT count(*) FROM t_operations WHERE workspace_id = :ws")
		.param("ws", WORKSPACE)
		.query(Int::class.java).single()

	private companion object {
		const val ADMIN_ROLE = "ADMIN"
		val WORKSPACE: UUID = UUID.fromString("0199a1c2-3d4e-7f80-8123-000000000001")
		const val REPLAY_PATH =
			"/admin/api/v1/workspaces/0199a1c2-3d4e-7f80-8123-000000000001/replay"
	}
}
