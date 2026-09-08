package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.api.v1.dto.CreateWorkspaceRequest
import com.github.melancholic.fintrace.core.config.ROOT_OTHERS_CAT_NAME
import com.github.melancholic.fintrace.core.dao.UsersDAO
import com.github.melancholic.fintrace.core.dao.WorkspaceDAO
import com.github.melancholic.fintrace.core.domain.command.CreateCategoryCommand
import com.github.melancholic.fintrace.core.domain.command.ReviseCategoryCommand
import com.github.melancholic.fintrace.core.domain.command.SetCategoryArchivedCommand
import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import com.github.melancholic.fintrace.core.domain.entity.CategorySystemCode
import com.github.melancholic.fintrace.core.domain.event.payload.CategoryCreatedV1
import com.github.melancholic.fintrace.core.domain.event.payload.CategoryEventPayload
import com.github.melancholic.fintrace.core.domain.event.payload.EventPayload
import com.github.melancholic.fintrace.core.domain.projection.CategoryProjection
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper
import java.util.*
import kotlin.test.*

/**
 * The category aggregate: the tree and its invariants (1.14).
 *
 * Several tests here assert rules that are **not implemented yet** and fail deliberately — they
 * describe the contract §4.7 defines, and each says what it expects. They are marked *pending*.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@WithMockUser(username = TestWorkspaces.TEST_SUBJECT)
class CategoryCommandIntegrationTest(
    @Autowired private val facade: CommandFacade,
    @Autowired private val workspaceFacade: WorkspaceFacade,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val mapper: ObjectMapper,
    @Autowired private val workspaceDAO: WorkspaceDAO,
    @Autowired private val usersDAO: UsersDAO,
) {

    private lateinit var workspaceId: UUID

    @BeforeEach
    fun clean() {
        TestWorkspaces.reset(jdbc)
        // Through the facade, not the DAO fixture: seeding the system categories is part of
        // creating a workspace, and these tests are about what it seeds.
        workspaceId = workspaceFacade.createWorkspace(CreateWorkspaceRequest("categories-test", "EUR")).id
    }

    // ---------------------------------------------------------------- seeding

    @Test
    fun `seeds four system categories with the workspace`() {
        // §4.7: both roots and both Others, all immutable. Without an Others there is nothing for
        // Core to assign an uncategorised operation to (2.14).
        val seeded = categories()

        assertEquals(4, seeded.size, "expected two roots and two Others, found ${seeded.map { it.name }}")
        assertEquals(
            setOf(
                CategorySystemCode.INCOME_ROOT,
                CategorySystemCode.INCOME_OTHERS,
                CategorySystemCode.EXPENSE_ROOT,
                CategorySystemCode.EXPENSE_OTHERS,
            ),
            seeded.mapNotNull { it.systemCode }.toSet(),
            "each seeded category carries its own code — the code, not the name, is what identifies it",
        )
        assertEquals(2, seeded.count { it.parentId == null }, "the two roots have no parent")
        assertEquals(2, seeded.count { it.parentId != null }, "both Others hang off their root")
    }

    @Test
    fun `seeds one root per kind`() {
        assertEquals(1, roots().count { it.kind == CategoryKind.INCOME })
        assertEquals(1, roots().count { it.kind == CategoryKind.EXPENSE })
    }

    @Test
    fun `seeding does not activate the workspace`() {
        // The seed goes through the dispatcher, not CommandFacade — otherwise creating a workspace
        // would activate it and close import before the user saw the screen (§4.1.1).
        val status = jdbc.sql("SELECT status FROM t_workspaces WHERE id = :id")
            .param("id", workspaceId).query(String::class.java).single()

        assertEquals("NEW", status)
    }

    // ---------------------------------------------------------------- create

    @Test
    fun `creates a child of a root and inherits its kind`() {
        val food = create(parent = expenseRoot().id, name = "Food")

        assertEquals(CategoryKind.EXPENSE, food.kind, "kind comes from the branch (§4.7), not the client")
        assertNull(food.systemCode, "a user category carries no system code")
        assertFalse(food.archived)
        assertEquals(expenseRoot().id, food.parentId)
    }

    @Test
    fun `classifies the event as a category creation`() {
        val food = create(parent = expenseRoot().id, name = "Food")

        val event = events().last { it.entityId == food.id }
        assertEquals("CATEGORY", event.entityType)
        assertEquals("CREATED", event.eventType)
        assertTrue(payload(event.payload) is CategoryCreatedV1)
    }

    @Test
    fun `nests to any depth`() {
        val food = create(parent = expenseRoot().id, name = "Food")
        val groceries = create(parent = food.id, name = "Groceries")
        val fruit = create(parent = groceries.id, name = "Fruit")

        assertEquals(CategoryKind.EXPENSE, fruit.kind)
        assertEquals(groceries.id, fruit.parentId)
    }

    // ---------------------------------------------------------------- revise

    @Test
    fun `renames a category and keeps what the command cannot carry`() {
        val food = create(parent = expenseRoot().id, name = "Food")

        val renamed = facade.processCommand(
            ReviseCategoryCommand(workspaceId, food.id, food.parentId, "Dining", icon = "fork")
        )

        assertEquals("Dining", renamed.name)
        assertEquals(CategoryKind.EXPENSE, renamed.kind, "kind is carried forward")
        assertNull(renamed.systemCode, "a revision does not promote a user category to a system one")
        assertFalse(renamed.archived)
    }

    @Test
    fun `moves a category within its branch`() {
        val food = create(parent = expenseRoot().id, name = "Food")
        val groceries = create(parent = expenseRoot().id, name = "Groceries")

        val moved = facade.processCommand(
            ReviseCategoryCommand(workspaceId, groceries.id, food.id, groceries.name, groceries.icon)
        )

        assertEquals(food.id, moved.parentId)
    }

    // ---------------------------------------------------------------- archive

    @Test
    fun `archiving cascades to the whole subtree`() {
        val food = create(parent = expenseRoot().id, name = "Food")
        val groceries = create(parent = food.id, name = "Groceries")
        val fruit = create(parent = groceries.id, name = "Fruit")

        facade.processCommand(SetCategoryArchivedCommand(workspaceId, food.id, archived = true))

        assertTrue(category(food.id).archived)
        assertTrue(category(groceries.id).archived, "a child must not survive its parent's archiving")
        assertTrue(category(fruit.id).archived, "the cascade reaches the whole subtree, not one level")
    }

    @Test
    fun `each archived node gets its own event`() {
        val food = create(parent = expenseRoot().id, name = "Food")
        val groceries = create(parent = food.id, name = "Groceries")
        val before = events().size

        facade.processCommand(SetCategoryArchivedCommand(workspaceId, food.id, archived = true))

        // Recording the children's archiving only on the parent's stream would leave each child's
        // own newest payload saying archived = false (§4.7).
        assertEquals(before + 2, events().size)
        assertTrue(latestPayloadOf(groceries.id).archived, "the child's own newest payload must say archived")
    }

    @Test
    fun `archiving a leaf touches nothing else`() {
        val food = create(parent = expenseRoot().id, name = "Food")
        val groceries = create(parent = food.id, name = "Groceries")

        facade.processCommand(SetCategoryArchivedCommand(workspaceId, groceries.id, archived = true))

        assertTrue(category(groceries.id).archived)
        assertFalse(category(food.id).archived, "archiving a child must not archive its parent")
    }

    @Test
    fun `restoring brings back only the target`() {
        val food = create(parent = expenseRoot().id, name = "Food")
        val groceries = create(parent = food.id, name = "Groceries")
        facade.processCommand(SetCategoryArchivedCommand(workspaceId, food.id, archived = true))

        facade.processCommand(SetCategoryArchivedCommand(workspaceId, food.id, archived = false))

        // Deliberate asymmetry: archiving cascades down, restoring does not, so nothing the user
        // archived deliberately is ever resurrected.
        assertFalse(category(food.id).archived)
        assertTrue(category(groceries.id).archived)
    }

    @Test
    fun `archiving twice changes nothing`() {
        val food = create(parent = expenseRoot().id, name = "Food")
        facade.processCommand(SetCategoryArchivedCommand(workspaceId, food.id, archived = true))
        val after = events().size

        facade.processCommand(SetCategoryArchivedCommand(workspaceId, food.id, archived = true))

        assertTrue(category(food.id).archived)
        assertEquals(after, events().size, "an already-archived node must not append a second event")
    }

    // ------------------------------------------------- pending: tree invariants (1.14)

    @Test
    fun `pending - rejects a child of a category that does not exist`() {
        val before = categories().size

        // Expected: 404. Without it, a dangling parent_id makes the whole branch unreachable from
        // any root, so the tree silently loses it.
        assertFails { create(parent = UUID.randomUUID(), name = "Orphan") }

        assertEquals(before, categories().size)
    }

    @Test
    fun `pending - rejects a child of a category in another workspace`() {
        val other = workspaceFacade.createWorkspace(CreateWorkspaceRequest("other-workspace", "EUR")).id
        val theirRoot = jdbc.sql("SELECT id FROM t_categories WHERE workspace_id = :ws AND parent_id IS NULL LIMIT 1")
            .param("ws", other).query(UUID::class.java).single()
        val before = categories().size

        assertFails { create(parent = theirRoot, name = "Stolen") }

        assertEquals(before, categories().size)
    }

    @Test
    fun `pending - rejects a child of an archived category`() {
        val food = create(parent = expenseRoot().id, name = "Food")
        facade.processCommand(SetCategoryArchivedCommand(workspaceId, food.id, archived = true))
        val before = categories().size

        assertFails { create(parent = food.id, name = "Groceries") }

        assertEquals(before, categories().size)
    }

    @Test
    fun `pending - rejects a child of an Others category`() {
        // §4.7: both Others are leaves — "Uncategorised → Food" is meaningless.
        val others = othersOf(CategoryKind.EXPENSE)
        val before = categories().size

        assertFails { create(parent = others.id, name = "Groceries") }

        assertEquals(before, categories().size)
    }

    @Test
    fun `a user category named Others is not one`() {
        // The leaf rule keys off the system code, not the name. Nothing stops a user naming their
        // own category 'Others' — and there is no sibling-name uniqueness rule — so a name-based
        // check refuses to nest anything under a perfectly ordinary category.
        val impostor = create(parent = expenseRoot().id, name = ROOT_OTHERS_CAT_NAME)
        val food = create(parent = expenseRoot().id, name = "Food")

        val groceries = create(parent = impostor.id, name = "Groceries")
        val moved = facade.processCommand(
            ReviseCategoryCommand(workspaceId, food.id, impostor.id, food.name, food.icon)
        )

        assertNull(impostor.systemCode, "only the seeded four carry a code; the name is a coincidence")
        assertEquals(impostor.id, groceries.parentId, "creating under it is allowed")
        assertEquals(impostor.id, moved.parentId, "moving under it is allowed")
    }

    @Test
    fun `pending - rejects renaming a system category`() {
        val root = expenseRoot()

        assertFails {
            facade.processCommand(ReviseCategoryCommand(workspaceId, root.id, null, "Spending", root.icon))
        }

        assertEquals(root.name, category(root.id).name)
    }

    @Test
    fun `pending - rejects archiving a system category`() {
        val root = expenseRoot()
        create(parent = root.id, name = "Food")

        // Archiving a root would cascade over every expense category in the workspace — and take
        // the Others that operations fall back to with it.
        assertFails { facade.processCommand(SetCategoryArchivedCommand(workspaceId, root.id, archived = true)) }

        assertFalse(category(root.id).archived)
        assertTrue(categories().none { it.archived }, "nothing in the branch may be archived either")
    }

    @Test
    fun `pending - rejects a move across branches`() {
        val food = create(parent = expenseRoot().id, name = "Food")

        // Moving an EXPENSE category under the INCOME root would leave its stored kind disagreeing
        // with its branch, and would rewrite the meaning of every operation filed under it (§4.7).
        assertFails {
            facade.processCommand(ReviseCategoryCommand(workspaceId, food.id, incomeRoot().id, food.name, food.icon))
        }

        assertEquals(expenseRoot().id, category(food.id).parentId)
    }

    @Test
    fun `pending - rejects a move into the category's own descendant`() {
        val food = create(parent = expenseRoot().id, name = "Food")
        val groceries = create(parent = food.id, name = "Groceries")

        // A cycle cannot be undone by a replay: the corruption is permanent in the log.
        assertFails {
            facade.processCommand(ReviseCategoryCommand(workspaceId, food.id, groceries.id, food.name, food.icon))
        }

        assertEquals(expenseRoot().id, category(food.id).parentId)
    }

    @Test
    fun `pending - rejects a move into itself`() {
        val food = create(parent = expenseRoot().id, name = "Food")

        assertFails {
            facade.processCommand(ReviseCategoryCommand(workspaceId, food.id, food.id, food.name, food.icon))
        }

        assertEquals(expenseRoot().id, category(food.id).parentId)
    }

    // ---------------------------------------------------------------- helpers

    private fun create(parent: UUID, name: String, icon: String? = null): CategoryProjection =
        facade.processCommand(
            CreateCategoryCommand.custom(
                workspaceId = workspaceId,
                kind = CategoryKind.EXPENSE,
                name = name,
                parentId = parent,
                icon = icon,
            )
        )

    private fun categories(): List<CategoryProjection> = jdbc
        .sql(
            """
            SELECT id, workspace_id, parent_id, name, kind, icon, archived, system_code, recorded_at
            FROM t_categories WHERE workspace_id = :ws ORDER BY recorded_at, id
            """
        )
        .param("ws", workspaceId)
        .query(CategoryProjection::class.java)
        .list()
        .filterNotNull()

    private fun roots() = categories().filter { it.parentId == null }

    private fun expenseRoot() = roots().single { it.kind == CategoryKind.EXPENSE }

    private fun incomeRoot() = roots().single { it.kind == CategoryKind.INCOME }

    private fun othersOf(kind: CategoryKind) = categories()
        .singleOrNull { it.systemCode == othersCodeOf(kind) }
        ?: error("no Others category was seeded for $kind — §4.7 requires four system categories")

    private fun othersCodeOf(kind: CategoryKind) = when (kind) {
        CategoryKind.INCOME -> CategorySystemCode.INCOME_OTHERS
        CategoryKind.EXPENSE -> CategorySystemCode.EXPENSE_OTHERS
    }

    private fun category(id: UUID) = categories().single { it.id == id }

    private fun payload(json: String): EventPayload = mapper.readValue(json, EventPayload::class.java)

    private fun latestPayloadOf(id: UUID): CategoryEventPayload = payload(
        jdbc.sql("SELECT payload FROM t_events WHERE aggregate_id = :id ORDER BY id DESC LIMIT 1")
            .param("id", id).query(String::class.java).single()
    ) as CategoryEventPayload

    private fun events(): List<EventRow> = jdbc
        .sql("SELECT aggregate_type, aggregate_id, event_type, payload FROM t_events WHERE workspace_id = :ws ORDER BY id")
        .param("ws", workspaceId)
        .query { rs, _ ->
            EventRow(
                entityType = rs.getString("aggregate_type"),
                entityId = rs.getObject("aggregate_id", UUID::class.java),
                eventType = rs.getString("event_type"),
                payload = rs.getString("payload"),
            )
        }
        .list()

    private data class EventRow(
        val entityType: String,
        val entityId: UUID,
        val eventType: String,
        val payload: String,
    )
}
