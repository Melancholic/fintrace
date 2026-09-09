package com.github.melancholic.fintrace.core.domain.entity

import com.github.melancholic.fintrace.core.exception.OperationNotAllowedException
import java.math.BigDecimal

enum class OperationKind {
    INCOME, EXPENSE, TRANSFER;

    fun signedAmount(amount: BigDecimal): BigDecimal = when (this) {
        INCOME -> return amount
        EXPENSE -> return amount.negate()
        TRANSFER -> throw OperationNotAllowedException()
    }

    fun asCategoryKind() = when (this) {
        INCOME -> CategoryKind.INCOME
        EXPENSE -> CategoryKind.EXPENSE
        TRANSFER -> throw OperationNotAllowedException()
    }
}