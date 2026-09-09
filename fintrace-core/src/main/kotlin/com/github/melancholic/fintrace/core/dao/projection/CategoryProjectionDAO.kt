package com.github.melancholic.fintrace.core.dao.projection

import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import com.github.melancholic.fintrace.core.domain.entity.CategorySystemCode
import com.github.melancholic.fintrace.core.domain.projection.CategoryProjection
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.service.projection.ProjectionTarget
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.*


interface CategoryProjectionDAO : ProjectionDAO<CategoryProjection> {
    override fun projectionTarget() = ProjectionTarget.CATEGORY
    override fun supportedClass() = CategoryProjection::class.java

    override fun createOrUpdate(projection: CategoryProjection): UUID
    override fun getById(workspaceId: UUID, categoryId: UUID): CategoryProjection
    fun getByIdAsOptional(workspaceId: UUID, categoryId: UUID): Optional<CategoryProjection>
    fun remove(workspaceId: UUID, categoryId: UUID)
    fun exists(workspaceId: UUID, categoryId: UUID): Boolean
    fun getAllCategories(workspaceId: UUID, includeArchived: Boolean): List<CategoryProjection>
    fun findSubtreeIds(workspaceId: UUID, categoryId: UUID): List<UUID>
    fun getFallbackCategory(workspaceId: UUID, categoryKind: CategoryKind): CategoryProjection
    fun getBySystemCode(workspaceId: UUID, systemCode: CategorySystemCode): CategoryProjection
}


@Repository
class CategoryProjectionDAOImpl(
    private val jdbc: JdbcClient
) : CategoryProjectionDAO {
    override fun createOrUpdate(projection: CategoryProjection): UUID {
        return jdbc.sql(INSERT_OR_UPDATE)
            .param("id", projection.id)
            .param("workspaceId", projection.workspaceId)
            .param("parentId", projection.parentId)
            .param("name", projection.name)
            .param("kind", projection.kind.name)
            .param("icon", projection.icon)
            .param("archived", projection.archived)
            .param("systemCode", projection.systemCode?.name)
            .param("recordedAt", projection.recordedAt)
            .query(UUID::class.java)
            .single()
    }

    override fun getById(
        workspaceId: UUID, categoryId: UUID
    ): CategoryProjection = getByIdAsOptional(workspaceId, categoryId)
        .orElseThrow { NotFoundEntityException("Category not found into workspace (workspaceId='$workspaceId', categoryId='$categoryId')") }

    override fun getByIdAsOptional(
        workspaceId: UUID,
        categoryId: UUID
    ): Optional<CategoryProjection> = jdbc.sql(SELECT_BY_ID).param("id", categoryId).param("workspaceId", workspaceId)
        .query(CategoryProjection::class.java).optional()

    override fun remove(workspaceId: UUID, categoryId: UUID) {
        remove(workspaceId, setOf(categoryId))
    }

    override fun exists(workspaceId: UUID, categoryId: UUID): Boolean {
        return jdbc.sql(CHECK_EXISTS).param("id", categoryId).param("workspaceId", workspaceId)
            .query(Boolean::class.java).single()
    }

    override fun getAllCategories(
        workspaceId: UUID,
        includeArchived: Boolean
    ): List<CategoryProjection> {

        var sql = SELECT_ALL
        if (!includeArchived) {
            sql += " AND not archived"
        }

        return jdbc.sql(sql)
            .param("workspaceId", workspaceId)
            .query(CategoryProjection::class.java)
            .list()
            .filterNotNull()
    }

    override fun findSubtreeIds(
        workspaceId: UUID,
        categoryId: UUID
    ): List<UUID> = jdbc.sql(FIND_SUBTREES_IDS)
        .param("workspaceId", workspaceId)
        .param("categoryId", categoryId)
        .query(UUID::class.java)
        .list() as List<UUID>

    override fun getFallbackCategory(
        workspaceId: UUID,
        categoryKind: CategoryKind
    ): CategoryProjection = when (categoryKind) {
        CategoryKind.INCOME -> getBySystemCode(workspaceId, CategorySystemCode.INCOME_OTHERS)
        CategoryKind.EXPENSE -> getBySystemCode(workspaceId, CategorySystemCode.EXPENSE_OTHERS)
    }

    override fun getBySystemCode(
        workspaceId: UUID,
        systemCode: CategorySystemCode
    ): CategoryProjection = jdbc.sql(SELECT_BY_SYS_CODE)
        .param("workspaceId", workspaceId)
        .param("systemCode", systemCode.name)
        .query(CategoryProjection::class.java)
        .single()

    override fun removeAll(workspaceId: UUID) {
        jdbc.sql(DELETE_BY_WORKSPACE).param("workspaceId", workspaceId).update()
    }

    override fun remove(workspaceId: UUID, ids: Set<UUID>) {
        jdbc.sql(DELETE_BY_IDS_AND_WORKSPACE).param("workspaceId", workspaceId).param("ids", ids).update()
    }

    companion object {
        const val TABLE_NAME = "t_categories"

        const val INSERT_OR_UPDATE = """
            INSERT INTO $TABLE_NAME (id, workspace_id, parent_id, name, kind, icon, archived, system_code, recorded_at)
            VALUES (:id, :workspaceId, :parentId, :name, :kind, :icon, :archived, :systemCode, :recordedAt) ON CONFLICT (id) DO
            UPDATE SET
                name = EXCLUDED.name,
                icon = EXCLUDED.icon,
                archived = EXCLUDED.archived,
                system_code = EXCLUDED.system_code,
                recorded_at = EXCLUDED.recorded_at
            WHERE $TABLE_NAME.workspace_id = EXCLUDED.workspace_id
            RETURNING id
        """

        const val SELECT_ALL = """
            SELECT *
            from $TABLE_NAME
            WHERE workspace_id = :workspaceId
        """

        const val SELECT_BY_ID = SELECT_ALL + """
            AND id = :id
        """

        const val SELECT_BY_SYS_CODE = SELECT_ALL + """
            AND system_code = :systemCode
        """

        const val CHECK_EXISTS = """
            SELECT EXISTS(SELECT 1 FROM $TABLE_NAME WHERE workspace_id = :workspaceId AND id = :id)
        """

        const val DELETE_BY_WORKSPACE = """
            DELETE from $TABLE_NAME WHERE workspace_id = :workspaceId
        """

        const val DELETE_BY_IDS_AND_WORKSPACE = """
            DELETE from $TABLE_NAME WHERE workspace_id = :workspaceId AND id IN (:ids)
        """

        const val FIND_SUBTREES_IDS = """
            WITH RECURSIVE subtree AS (
                SELECT id FROM $TABLE_NAME 
                WHERE workspace_id = :workspaceId AND id = :categoryId 
                    
                UNION
                
                SELECT c.id FROM $TABLE_NAME c
                INNER JOIN subtree s ON s.id = c.parent_id
                WHERE workspace_id = :workspaceId
            )
            SELECT id FROM subtree
        """
    }
}