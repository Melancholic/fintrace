package com.github.melancholic.fintrace.core.domain.entity

enum class CategorySystemCode {
    INCOME_ROOT, INCOME_OTHERS, EXPENSE_ROOT, EXPENSE_OTHERS;

    companion object {
        val OTHERS = setOf(INCOME_OTHERS, EXPENSE_OTHERS)
    }
}