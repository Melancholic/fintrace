package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.dao.UsersDAO
import com.github.melancholic.fintrace.core.dao.WorkspaceDAO
import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.CategoryProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.math.BigDecimal
import java.net.URI
import java.util.*
import kotlin.test.assertEquals

/**
 * The HTTP contract for `/transfers` (1.20). A transfer has no row of its own — it is assembled
 * from the two legs in `t_operations` plus their accounts' currencies — so this is where that
 * assembly is checked against a real Postgres, along with the request shapes: the write path is
 * the only place `CreateTransferRequest` and its Jakarta rules are ever exercised.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class TransfersRestControllerTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val workspaceDAO: WorkspaceDAO,
    @Autowired private val usersDAO: UsersDAO,
    @Autowired private val accountDAO: AccountProjectionDAO,
    @Autowired private val categoryDAO: CategoryProjectionDAO,
    @Autowired private val operationDAO: OperationProjectionDAO,
) {

    private lateinit var workspaceId: UUID
    private lateinit var euroAccount: UUID
    private lateinit var poundAccount: UUID
    private lateinit var categoryId: UUID

    private val transfersPath get() = "/api/v1/workspaces/$workspaceId/transfers"

    @BeforeEach
    fun clean() {
        TestWorkspaces.reset(jdbc)
        workspaceId = TestWorkspaces.create(workspaceDAO, usersDAO)
        euroAccount = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "euro", currency = "EUR")
        poundAccount = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "pound", currency = "GBP")
        categoryId = TestWorkspaces.seedCategory(categoryDAO, workspaceId)
    }

    @Test
    fun `reads a transfer as one thing, with both legs`() {
        val transferId = UUID.randomUUID()
        val (source, target) = seedTransfer(transferId)

        mvc.perform(get("$transfersPath/$transferId").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(transferId.toString()))
            .andExpect(jsonPath("$.workspaceId").value(workspaceId.toString()))
            .andExpect(jsonPath("$.source.operationId").value(source.toString()))
            .andExpect(jsonPath("$.source.accountId").value(euroAccount.toString()))
            .andExpect(jsonPath("$.target.operationId").value(target.toString()))
            .andExpect(jsonPath("$.target.accountId").value(poundAccount.toString()))
    }

    @Test
    fun `answers with absolute amounts and each leg's own currency`() {
        val transferId = UUID.randomUUID()
        seedTransfer(transferId)

        // The outgoing leg is stored negative (§4.13) and absolute again on the wire, and a
        // cross-currency pair carries two different currencies — neither derived from the other.
        mvc.perform(get("$transfersPath/$transferId").with(user(USER)))
            .andExpect(jsonPath("$.source.amount").value(100.0000))
            .andExpect(jsonPath("$.source.currency").value("EUR"))
            .andExpect(jsonPath("$.target.amount").value(85.0000))
            .andExpect(jsonPath("$.target.currency").value("GBP"))
    }

    @Test
    fun `derives the rate as destination units per one unit of source`() {
        val transferId = UUID.randomUUID()
        seedTransfer(transferId)

        // 85 GBP for 100 EUR. Derived on the way out, never stored: the two amounts are the only
        // source of truth.
        mvc.perform(get("$transfersPath/$transferId").with(user(USER)))
            .andExpect(jsonPath("$.rate").value(0.850000))
    }

    @Test
    fun `carries the pair's comment`() {
        val transferId = UUID.randomUUID()
        seedTransfer(transferId, comment = "rent")

        mvc.perform(get("$transfersPath/$transferId").with(user(USER)))
            .andExpect(jsonPath("$.comment").value("rent"))
    }

    @Test
    fun `refuses to read a transfer from another workspace`() {
        val transferId = UUID.randomUUID()
        seedTransfer(transferId)
        val other = TestWorkspaces.create(workspaceDAO, usersDAO, name = "other-workspace")

        // Scoped by both keys: an id you cannot reach must look exactly like one that never existed.
        mvc.perform(get("/api/v1/workspaces/$other/transfers/$transferId").with(user(USER)))
            .andExpect(status().isNotFound)
    }

    // ------------------------------------------------------------------ write path (1.18, 1.20)

    @Test
    fun `creates a transfer and returns both legs`() {
        mvc.perform(createRequest())
            .andExpect(status().isCreated)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.source.accountId").value(euroAccount.toString()))
            .andExpect(jsonPath("$.source.currency").value("EUR"))
            .andExpect(jsonPath("$.source.amount").value(100.0000))
            .andExpect(jsonPath("$.target.accountId").value(poundAccount.toString()))
            .andExpect(jsonPath("$.target.currency").value("GBP"))
            .andExpect(jsonPath("$.target.amount").value(85.0000))
            .andExpect(jsonPath("$.rate").value(0.850000))
    }

    @Test
    fun `points the Location header at the created transfer`() {
        val response = mvc.perform(createRequest()).andExpect(status().isCreated).andReturn().response
        val id = idOf(response.contentAsString)

        assertEquals("$transfersPath/$id", URI.create(response.getHeader("Location")!!).path)

        // The header is only useful if it actually resolves — follow it.
        mvc.perform(get(URI.create(response.getHeader("Location")!!).path).with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(id))
    }

    @Test
    fun `stores the source leg negative and writes one event`() {
        val id = createdId()

        // The wire carries magnitudes; the direction decides the sign (§4.13).
        assertEquals(BigDecimal("-100.0000"), storedAmount(euroAccount))
        assertEquals(BigDecimal("85.0000"), storedAmount(poundAccount))
        assertEquals(1, eventCount())
        assertEquals(2, legCount())
    }

    @Test
    fun `revises a transfer and answers with its new state`() {
        val id = createdId()

        mvc.perform(reviseRequest(id, sourceAmount = "120.0000", targetAmount = "100.0000"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(id.toString()))
            .andExpect(jsonPath("$.source.amount").value(120.0000))
            .andExpect(jsonPath("$.target.amount").value(100.0000))

        assertEquals(2, legCount(), "a revision replaces rather than adds")
        assertEquals(2, eventCount())
    }

    @Test
    fun `cancels a transfer and answers with no content`() {
        val id = createdId()

        mvc.perform(delete("$transfersPath/$id").with(user(USER)).with(csrf()))
            .andExpect(status().isNoContent)

        mvc.perform(get("$transfersPath/$id").with(user(USER))).andExpect(status().isNotFound)
        assertEquals(0, legCount())
        assertEquals(2, eventCount(), "the cancellation is recorded even though the rows are gone")
    }

    @Test
    fun `rejects a non-positive amount`() {
        // `@Positive` on the leg request — the only place these Jakarta rules are exercised.
        mvc.perform(createRequest(sourceAmount = "-100.0000")).andExpect(status().isBadRequest)
        mvc.perform(createRequest(targetAmount = "0")).andExpect(status().isBadRequest)

        assertEquals(0, eventCount(), "a rejected command writes nothing")
    }

    @Test
    fun `rejects a transfer between one and the same account`() {
        mvc.perform(createRequest(targetAccountId = euroAccount)).andExpect(status().isBadRequest)

        assertEquals(0, eventCount())
    }

    @Test
    fun `rejects a malformed body`() {
        mvc.perform(
            post(transfersPath).with(user(USER)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"occurredAt":"2026-03-15T14:30:00","source":{"amount":"nope"}}""")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `rejects a revision of an unknown transfer`() {
        mvc.perform(reviseRequest(UUID.randomUUID())).andExpect(status().isNotFound)
    }

    @Test
    fun `rejects an unauthenticated create`() {
        mvc.perform(post(transfersPath).contentType(MediaType.APPLICATION_JSON).content(body()))
            .andExpect(status().isForbidden)

        assertEquals(0, eventCount(), "nothing may be written for an unauthenticated caller")
    }

    @Test
    fun `rejects an unauthenticated read`() {
        mvc.perform(get("$transfersPath/${UUID.randomUUID()}"))
            .andExpect(status().isForbidden)
    }

    private fun createRequest(
        sourceAccountId: UUID = this.euroAccount,
        sourceAmount: String = "100.0000",
        targetAccountId: UUID = this.poundAccount,
        targetAmount: String = "85.0000",
    ) = post(transfersPath)
        .with(user(USER))
        .with(csrf())
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(sourceAccountId, sourceAmount, targetAccountId, targetAmount))

    private fun reviseRequest(
        transferId: UUID,
        sourceAmount: String = "100.0000",
        targetAmount: String = "85.0000",
    ) = put("$transfersPath/$transferId")
        .with(user(USER))
        .with(csrf())
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(euroAccount, sourceAmount, poundAccount, targetAmount))

    private fun createdId(): UUID = UUID.fromString(
        idOf(mvc.perform(createRequest()).andExpect(status().isCreated).andReturn().response.contentAsString)
    )

    private fun body(
        sourceAccountId: UUID = this.euroAccount,
        sourceAmount: String = "100.0000",
        targetAccountId: UUID = this.poundAccount,
        targetAmount: String = "85.0000",
    ) = """
        {"occurredAt":"2026-03-15T14:30:00",
         "source":{"accountId":"$sourceAccountId","amount":"$sourceAmount"},
         "target":{"accountId":"$targetAccountId","amount":"$targetAmount"},
         "comment":"rent"}
        """

    private fun idOf(json: String) = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(json)!!.groupValues[1]

    private fun storedAmount(accountId: UUID) = jdbc
        .sql("SELECT amount FROM t_operations WHERE account_id = :id")
        .param("id", accountId)
        .query(BigDecimal::class.java).single()

    private fun legCount() = jdbc.sql("SELECT count(*) FROM t_operations")
        .query(Int::class.java).single()

    private fun eventCount() = jdbc.sql("SELECT count(*) FROM t_events")
        .query(Int::class.java).single()

    private fun seedTransfer(transferId: UUID, comment: String? = null) = TestWorkspaces.seedTransferPair(
        operationDAO,
        workspaceId,
        sourceAccountId = euroAccount,
        targetAccountId = poundAccount,
        transferId = transferId,
        sourceAmount = BigDecimal("100.0000"),
        targetAmount = BigDecimal("85.0000"),
        comment = comment,
    )

    private companion object {
        val USER = TestWorkspaces.TEST_SUBJECT
    }
}
