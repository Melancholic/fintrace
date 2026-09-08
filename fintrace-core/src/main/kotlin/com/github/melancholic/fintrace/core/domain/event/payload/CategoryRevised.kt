package com.github.melancholic.fintrace.core.domain.event.payload

import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import com.github.melancholic.fintrace.core.domain.entity.CategorySystemCode
import java.time.LocalDateTime
import java.util.*

sealed interface CategoryRevised : CategoryEventPayload

/**
 * WARNING: Shouldn't be changed ever.
 * In case of any changes required, have to create a next version of entity.
 */
data class CategoryRevisedV1(
    override val id: UUID,
    override val workspaceId: UUID,
    override val parentId: UUID?,
    override val name: String,
    override val kind: CategoryKind,
    override val icon: String?,
    override val archived: Boolean,
    override val systemCode: CategorySystemCode?,
    override val recordedAt: LocalDateTime,
    override val version: Int = VERSION,
) : CategoryRevised {

    companion object {
        const val TYPE = "category.revised.v1"
        const val VERSION = 1
    }
}
