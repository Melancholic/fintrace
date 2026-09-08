package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.api.v1.dto.CreateWorkspaceRequest
import com.github.melancholic.fintrace.core.facade.WorkspaceFacade
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.context.support.WithMockUser
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
 * The HTTP contract for `/api/v1/workspaces/{workspaceId}/categories`.
 *
 * The workspace is created through its facade rather than the DAO fixture, because seeding the four
 * system categories is part of creating one — and half of what this endpoint returns is that seed.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = TestWorkspaces.TEST_SUBJECT)
class CategoriesRestControllerTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val workspaceFacade: WorkspaceFacade,
) {

    private lateinit var workspaceId: UUID
    private val categoriesPath get() = "/api/v1/workspaces/$workspaceId/categories"

    @BeforeEach
    fun clean() {
        TestWorkspaces.reset(jdbc)
        workspaceId = workspaceFacade.createWorkspace(CreateWorkspaceRequest("categories-api", "EUR")).id
    }

    @Test
    fun `lists the seeded system categories`() {
        mvc.perform(get(categoriesPath).with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(4))
            .andExpect(
                jsonPath(
                    "$[*].systemCode",
                    containsInAnyOrder("INCOME_ROOT", "INCOME_OTHERS", "EXPENSE_ROOT", "EXPENSE_OTHERS"),
                )
            )
    }

    @Test
    fun `creates a category and returns its state`() {
        // kind is absent from the request: it comes from the branch (§4.7), so the response is the
        // first place a client learns it.
        mvc.perform(createRequest(parent = expenseRootId(), name = "Food", icon = "burger"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.name").value("Food"))
            .andExpect(jsonPath("$.icon").value("burger"))
            .andExpect(jsonPath("$.kind").value("EXPENSE"))
            .andExpect(jsonPath("$.archived").value(false))
            .andExpect(jsonPath("$.systemCode").value(nullValue()))
            .andExpect(jsonPath("$.parentId").value(expenseRootId().toString()))
    }

    @Test
    fun `points the Location header at the created category`() {
        val response = mvc.perform(createRequest(parent = expenseRootId(), name = "Food"))
            .andExpect(status().isCreated).andReturn().response
        val location = URI.create(response.getHeader("Location")!!).path

        assertEquals("$categoriesPath/${idOf(response.contentAsString)}", location)
        mvc.perform(get(location).with(user(USER))).andExpect(status().isOk)
    }

    @Test
    fun `renames a category`() {
        val food = createdId(parent = expenseRootId(), name = "Food")

        mvc.perform(reviseRequest(food, parent = expenseRootId(), name = "Dining", icon = "fork"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Dining"))
            .andExpect(jsonPath("$.icon").value("fork"))
            .andExpect(jsonPath("$.kind").value("EXPENSE"))
    }

    @Test
    fun `moves a category by changing its parent`() {
        val food = createdId(parent = expenseRootId(), name = "Food")
        val groceries = createdId(parent = expenseRootId(), name = "Groceries")

        // A move is a revision that carries a different parent — there is no separate endpoint.
        mvc.perform(reviseRequest(groceries, parent = food, name = "Groceries"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.parentId").value(food.toString()))
    }

    @Test
    fun `archives a category with DELETE and restores it`() {
        val food = createdId(parent = expenseRootId(), name = "Food")

        mvc.perform(delete("$categoriesPath/$food").with(user(USER)).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.archived").value(true))

        mvc.perform(post("$categoriesPath/$food/restore").with(user(USER)).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.archived").value(false))
    }

    @Test
    fun `hides archived categories from the listing by default`() {
        val food = createdId(parent = expenseRootId(), name = "Food")
        createdId(parent = food, name = "Groceries")
        mvc.perform(delete("$categoriesPath/$food").with(user(USER)).with(csrf()))

        // Archiving cascaded to Groceries, so both disappear from the default listing while the
        // four system categories remain.
        mvc.perform(get(categoriesPath).with(user(USER)))
            .andExpect(jsonPath("$.length()").value(4))
        mvc.perform(get("$categoriesPath?includeArchived=true").with(user(USER)))
            .andExpect(jsonPath("$.length()").value(6))
    }

    @Test
    fun `an archived category is still fetchable by id`() {
        val food = createdId(parent = expenseRootId(), name = "Food")
        mvc.perform(delete("$categoriesPath/$food").with(user(USER)).with(csrf()))

        // Historical operations still reference it, so a report must be able to resolve its name.
        mvc.perform(get("$categoriesPath/$food").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Food"))
            .andExpect(jsonPath("$.archived").value(true))
    }

    @Test
    fun `returns 404 for a category that does not exist`() {
        mvc.perform(get("$categoriesPath/${UUID.randomUUID()}").with(user(USER)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `returns 404 for a category in another workspace`() {
        // Before the first MockMvc call: the filter chain clears the security context after each
        // request, so a direct facade call afterwards would have no authenticated caller.
        val other = workspaceFacade.createWorkspace(CreateWorkspaceRequest("other-workspace", "EUR")).id
        val food = createdId(parent = expenseRootId(), name = "Food")

        mvc.perform(get("/api/v1/workspaces/$other/categories/$food").with(user(USER)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `rejects a name outside the permitted pattern`() {
        mvc.perform(createRequest(parent = expenseRootId(), name = "bad name!"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `rejects a create with no parent`() {
        // Only the seed may seed a root; a client cannot make one.
        mvc.perform(
            post(categoriesPath).with(user(USER)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Rootish"}""")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `rejects modifying a system category`() {
        val root = expenseRootId()

        mvc.perform(reviseRequest(root, parent = root, name = "Spending"))
            .andExpect(status().is4xxClientError)
        mvc.perform(delete("$categoriesPath/$root").with(user(USER)).with(csrf()))
            .andExpect(status().is4xxClientError)

        mvc.perform(get("$categoriesPath/$root").with(user(USER)))
            .andExpect(jsonPath("$.archived").value(false))
    }

    @Test
    fun `rejects a move that would create a cycle`() {
        val food = createdId(parent = expenseRootId(), name = "Food")
        val groceries = createdId(parent = food, name = "Groceries")

        mvc.perform(reviseRequest(food, parent = groceries, name = "Food"))
            .andExpect(status().is4xxClientError)

        mvc.perform(get("$categoriesPath/$food").with(user(USER)))
            .andExpect(jsonPath("$.parentId").value(expenseRootId().toString()))
    }

    @Test
    fun `rejects a move across branches`() {
        val food = createdId(parent = expenseRootId(), name = "Food")

        mvc.perform(reviseRequest(food, parent = incomeRootId(), name = "Food"))
            .andExpect(status().is4xxClientError)
    }

    @Test
    fun `rejects an unauthenticated caller on every endpoint`() {
        val food = createdId(parent = expenseRootId(), name = "Food")

        mvc.perform(post(categoriesPath).contentType(MediaType.APPLICATION_JSON).content(body(expenseRootId(), "X")))
            .andExpect(status().isForbidden)
        mvc.perform(get(categoriesPath)).andExpect(status().isForbidden)
        mvc.perform(get("$categoriesPath/$food")).andExpect(status().isForbidden)
        mvc.perform(
            put("$categoriesPath/$food").contentType(MediaType.APPLICATION_JSON).content(body(expenseRootId(), "X"))
        ).andExpect(status().isForbidden)
        mvc.perform(delete("$categoriesPath/$food")).andExpect(status().isForbidden)
        mvc.perform(post("$categoriesPath/$food/restore")).andExpect(status().isForbidden)

        assertEquals(5, count(), "four seeded plus Food; nothing written for an unauthenticated caller")
    }

    private fun createRequest(parent: UUID, name: String, icon: String? = null) =
        post(categoriesPath).with(user(USER)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(parent, name, icon))

    private fun reviseRequest(id: UUID, parent: UUID, name: String, icon: String? = null) =
        put("$categoriesPath/$id").with(user(USER)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(parent, name, icon))

    private fun body(parent: UUID, name: String, icon: String? = null) =
        if (icon == null) """{"name":"$name","parentId":"$parent"}"""
        else """{"name":"$name","parentId":"$parent","icon":"$icon"}"""

    private fun createdId(parent: UUID, name: String): UUID =
        idOf(
            mvc.perform(createRequest(parent, name)).andExpect(status().isCreated).andReturn().response.contentAsString
        )

    private fun expenseRootId(): UUID = rootId("EXPENSE")

    private fun incomeRootId(): UUID = rootId("INCOME")

    private fun rootId(kind: String): UUID = jdbc
        .sql("SELECT id FROM t_categories WHERE workspace_id = :ws AND parent_id IS NULL AND kind = :kind")
        .param("ws", workspaceId)
        .param("kind", kind)
        .query(UUID::class.java)
        .single()

    private fun count() = jdbc.sql("SELECT count(*) FROM t_categories WHERE workspace_id = :ws")
        .param("ws", workspaceId)
        .query(Int::class.java).single()

    private fun idOf(json: String): UUID =
        UUID.fromString(Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(json)!!.groupValues[1])

    private companion object {
        val USER = TestWorkspaces.TEST_SUBJECT
    }
}
