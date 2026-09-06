package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.dao.UsersDAO
import com.github.melancholic.fintrace.core.dao.WorkspaceDAO
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.URI
import java.util.*
import kotlin.test.assertEquals

/**
 * The HTTP contract for `/api/v1/workspaces/{workspaceId}/accounts`, through the real filter
 * chain against a real Postgres.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class AccountsRestControllerTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val workspaceDAO: WorkspaceDAO,
    @Autowired private val usersDAO: UsersDAO,
) {

    private lateinit var workspaceId: UUID
    private val accountsPath get() = "/api/v1/workspaces/$workspaceId/accounts"

    @BeforeEach
    fun clean() {
        TestWorkspaces.reset(jdbc)
        workspaceId = TestWorkspaces.create(workspaceDAO, usersDAO)
    }

    @Test
    fun `creates an account and returns its state`() {
        // The response carries what the client could not know: the id, the server's recordedAt,
        // and the archived flag it never sent.
        mvc.perform(createRequest(body(name = "cash-eur", currency = "EUR", icon = "wallet")))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.name").value("cash-eur"))
            .andExpect(jsonPath("$.currency").value("EUR"))
            .andExpect(jsonPath("$.icon").value("wallet"))
            .andExpect(jsonPath("$.archived").value(false))
            .andExpect(jsonPath("$.recordedAt").exists())
    }

    @Test
    fun `points the Location header at the created account`() {
        val response = mvc.perform(createRequest()).andExpect(status().isCreated).andReturn().response
        val location = URI.create(response.getHeader("Location")!!).path

        assertEquals("$accountsPath/${idOf(response.contentAsString)}", location)

        mvc.perform(get(location).with(user(USER))).andExpect(status().isOk)
    }

    @Test
    fun `returns the account with the fields the mapper exposes`() {
        val id = createdId(name = "cash-eur", currency = "EUR", icon = "wallet")

        mvc.perform(get("$accountsPath/$id").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(id.toString()))
            .andExpect(jsonPath("$.name").value("cash-eur"))
            .andExpect(jsonPath("$.currency").value("EUR"))
            .andExpect(jsonPath("$.icon").value("wallet"))
            .andExpect(jsonPath("$.archived").value(false))
            .andExpect(jsonPath("$.recordedAt").exists())
    }

    @Test
    fun `lists the workspace's accounts`() {
        createdId(name = "first")
        createdId(name = "second")

        mvc.perform(get(accountsPath).with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `hides archived accounts from the listing by default`() {
        val kept = createdId(name = "kept")
        val archived = createdId(name = "gone")
        mvc.perform(delete("$accountsPath/$archived").with(user(USER)).with(csrf()))

        // §4.8: an archived account is not offered for selection, but is still reachable.
        mvc.perform(get(accountsPath).with(user(USER)))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(kept.toString()))

        mvc.perform(get("$accountsPath?includeArchived=true").with(user(USER)))
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `renames an account`() {
        val id = createdId(name = "before")

        mvc.perform(
            put("$accountsPath/$id").with(user(USER)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"after","icon":"new"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("after"))
            .andExpect(jsonPath("$.icon").value("new"))

        mvc.perform(get("$accountsPath/$id").with(user(USER)))
            .andExpect(jsonPath("$.name").value("after"))
            .andExpect(jsonPath("$.icon").value("new"))
    }

    @Test
    fun `a rename keeps the currency the client cannot send`() {
        val id = createdId(currency = "CZK")

        mvc.perform(
            put("$accountsPath/$id").with(user(USER)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"renamed"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.currency").value("CZK"))
    }

    @Test
    fun `archives an account with DELETE and restores it`() {
        val id = createdId()

        // DELETE means "make it go away as far as the model allows": an account is archived,
        // never deleted (§4.8), and POST /restore is the way back. Each answers with the account,
        // so a client needs no second request to render the change.
        mvc.perform(delete("$accountsPath/$id").with(user(USER)).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.archived").value(true))
        mvc.perform(get("$accountsPath/$id").with(user(USER)))
            .andExpect(jsonPath("$.archived").value(true))

        mvc.perform(post("$accountsPath/$id/restore").with(user(USER)).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.archived").value(false))
        mvc.perform(get("$accountsPath/$id").with(user(USER)))
            .andExpect(jsonPath("$.archived").value(false))
    }

    @Test
    fun `returns 404 for an account that does not exist`() {
        mvc.perform(get("$accountsPath/${UUID.randomUUID()}").with(user(USER)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `returns 404 for an account in another workspace`() {
        val id = createdId()
        val other = TestWorkspaces.create(workspaceDAO, usersDAO, name = "other-workspace")

        mvc.perform(get("/api/v1/workspaces/$other/accounts/$id").with(user(USER)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `rejects a name outside the permitted pattern`() {
        mvc.perform(createRequest("""{"name":"bad name!","currency":"EUR"}"""))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `rejects a currency that is not an ISO code`() {
        // "eur" fails the DTO pattern; "ZZZ" passes it and fails in the service.
        mvc.perform(createRequest("""{"name":"cash","currency":"eur"}"""))
            .andExpect(status().isBadRequest)
        mvc.perform(createRequest("""{"name":"cash","currency":"ZZZ"}"""))
            .andExpect(status().isBadRequest)

        assertEquals(0, count())
    }

    @Test
    fun `rejects an unauthenticated caller on every endpoint`() {
        val id = createdId()

        mvc.perform(post(accountsPath).contentType(MediaType.APPLICATION_JSON).content(body()))
            .andExpect(status().isForbidden)
        mvc.perform(get(accountsPath)).andExpect(status().isForbidden)
        mvc.perform(get("$accountsPath/$id")).andExpect(status().isForbidden)
        mvc.perform(
            put("$accountsPath/$id").contentType(MediaType.APPLICATION_JSON).content("""{"name":"x"}""")
        ).andExpect(status().isForbidden)
        mvc.perform(delete("$accountsPath/$id")).andExpect(status().isForbidden)
        mvc.perform(post("$accountsPath/$id/restore")).andExpect(status().isForbidden)

        assertEquals(1, count(), "nothing may be written for an unauthenticated caller")
    }

    private fun createRequest(body: String = body()) = post(accountsPath)
        .with(user(USER))
        .with(csrf())
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)

    private fun body(name: String = "account", currency: String = "EUR", icon: String? = null) =
        if (icon == null) """{"name":"$name","currency":"$currency"}"""
        else """{"name":"$name","currency":"$currency","icon":"$icon"}"""

    private fun createdId(name: String = "account", currency: String = "EUR", icon: String? = null): UUID =
        idOf(
            mvc.perform(createRequest(body(name, currency, icon)))
                .andExpect(status().isCreated).andReturn().response.contentAsString
        )

    private fun count() = jdbc.sql("SELECT count(*) FROM t_accounts").query(Int::class.java).single()

    private fun idOf(json: String): UUID =
        UUID.fromString(Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(json)!!.groupValues[1])

    private companion object {
        val USER = TestWorkspaces.TEST_SUBJECT
    }
}
