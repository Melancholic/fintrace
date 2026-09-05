package com.github.melancholic.fintrace.core.util

import com.github.melancholic.fintrace.core.exception.ValidationError
import org.springframework.data.domain.Sort

object SqlHelper {

    fun orderBy(sortableCols: Map<String, String>, fallback: List<String>, sort: Sort): String {
        val clauses = sort.mapNotNull { order ->
            val column = sortableCols[order.property]
                ?: throw ValidationError("Cannot sort by '${order.property}'")
            "$column ${if (order.isAscending) "ASC" else "DESC"}"
        }.ifEmpty { fallback }
        return (clauses + "id").joinToString(", ")
    }

}