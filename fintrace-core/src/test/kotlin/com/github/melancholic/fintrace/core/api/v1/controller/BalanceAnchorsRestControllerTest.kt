package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.dao.UsersDAO
import com.github.melancholic.fintrace.core.dao.WorkspaceDAO
import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
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
 * The HTTP contract for `/accounts/{accountId}/balance-anchors` (1.22, 1.23).
 *
 * The aggregate itself is covered at the facade level; what is checked here is the part only a
 * real request reaches — routing under the account, the `Location` header, and the two refusals
 * a client has to tell apart: an archived account and an anchor that is not the latest.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class BalanceAnchorsRestControllerTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val workspaceDAO: WorkspaceDAO,
    @Autowired private val usersDAO: UsersDAO,
    @Autowired private val accountDAO: AccountProjectionDAO,
) {

    private lateinit var workspaceId: UUID
    private lateinit var accountId: UUID
    private lateinit var otherAccountId: UUID

    private val anchorsPath get() = "/api/v1/workspaces/$workspaceId/accounts/$accountId/balance-anchors"

    @BeforeEach
    fun clean() {
        TestWorkspaces.reset(jdbc)
        workspaceId = TestWorkspaces.create(workspaceDAO, usersDAO)
        accountId = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "wallet")
        otherAccountId = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "savings")
    }

    @Test
    fun `creates an anchor and returns its state`() {
        mvc.perform(createRequest("1500.0000"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.workspaceId").value(workspaceId.toString()))
            .andExpect(jsonPath("$.accountId").value(accountId.toString()))
            .andExpect(jsonPath("$.value").value(1500.0000))
            .andExpect(jsonPath("$.recordedAt").exists())
    }

    @Test
    fun `points the Location header at the created anchor`() {
        val response = mvc.perform(createRequest()).andExpect(status().isCreated).andReturn().response
        val id = idOf(response.contentAsString)

        assertEquals("$anchorsPath/$id", URI.create(response.getHeader("Location")!!).path)

        // The header is only useful if it actually resolves — follow it.
        mvc.perform(get(URI.create(response.getHeader("Location")!!).path).with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(id))
    }

    @Test
    fun `lists an account's anchors and no one else's`() {
        mvc.perform(createRequest("10.0000")).andExpect(status().isCreated)
        mvc.perform(createRequest("20.0000")).andExpect(status().isCreated)
        mvc.perform(
            post("/api/v1/workspaces/$workspaceId/accounts/$otherAccountId/balance-anchors")
                .with(user(USER)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body("99.0000"))
        ).andExpect(status().isCreated)

        mvc.perform(get(anchorsPath).with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].accountId").value(accountId.toString()))
    }

    @Test
    fun `answers with an empty list for an account with no anchors`() {
        mvc.perform(get(anchorsPath).with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `accepts a negative value`() {
        // An overdraft is a real reading; an anchor is an observation, not a magnitude.
        mvc.perform(createRequest("-250.0000"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.value").value(-250.0000))
    }

    @Test
    fun `refuses an anchor on an archived account`() {
        archive(accountId)

        mvc.perform(createRequest()).andExpect(status().isConflict)

        assertEquals(0, count(), "a rejected command writes nothing")
    }

    @Test
    fun `rejects an anchor on an account that does not exist`() {
        mvc.perform(
            post("/api/v1/workspaces/$workspaceId/accounts/${UUID.randomUUID()}/balance-anchors")
                .with(user(USER)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body())
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `deletes the most recent anchor and answers with no content`() {
        val id = createdId()

        mvc.perform(delete("$anchorsPath/$id").with(user(USER)).with(csrf()))
            .andExpect(status().isNoContent)

        mvc.perform(get("$anchorsPath/$id").with(user(USER))).andExpect(status().isNotFound)
        assertEquals(0, count())
    }

    @Test
    fun `refuses to delete an anchor that is not the most recent`() {
        val older = createdId()
        createdId()

        // §10.4: removing one from the middle would shift every balance after it.
        mvc.perform(delete("$anchorsPath/$older").with(user(USER)).with(csrf()))
            .andExpect(status().isConflict)

        assertEquals(2, count())
    }

    @Test
    fun `refuses to reach an anchor through the wrong account`() {
        val id = createdId()

        mvc.perform(
            delete("/api/v1/workspaces/$workspaceId/accounts/$otherAccountId/balance-anchors/$id")
                .with(user(USER)).with(csrf())
        ).andExpect(status().isNotFound)

        assertEquals(1, count())
    }

    @Test
    fun `rejects a malformed body`() {
        mvc.perform(
            post(anchorsPath).with(user(USER)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""{"value":"not-a-number"}""")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `rejects an unauthenticated create`() {
        mvc.perform(post(anchorsPath).contentType(MediaType.APPLICATION_JSON).content(body()))
            .andExpect(status().isForbidden)

        assertEquals(0, count(), "nothing may be written for an unauthenticated caller")
    }

    @Test
    fun `rejects an unauthenticated read`() {
        mvc.perform(get(anchorsPath)).andExpect(status().isForbidden)
    }

    private fun createRequest(value: String = "100.0000") = post(anchorsPath)
        .with(user(USER))
        .with(csrf())
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(value))

    private fun createdId(): UUID = UUID.fromString(
        idOf(mvc.perform(createRequest()).andExpect(status().isCreated).andReturn().response.contentAsString)
    )

    private fun body(value: String = "100.0000") = """{"value":"$value"}"""

    private fun idOf(json: String) = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(json)!!.groupValues[1]

    private fun archive(id: UUID) = jdbc
        .sql("UPDATE t_accounts SET archived = true WHERE id = :id")
        .param("id", id)
        .update()

    private fun count() = jdbc
        .sql("SELECT count(*) FROM t_balance_anchors WHERE account_id = :id")
        .param("id", accountId)
        .query(Int::class.java).single()

    private companion object {
        val USER = TestWorkspaces.TEST_SUBJECT
    }
}
