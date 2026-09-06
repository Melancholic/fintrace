package com.github.melancholic.fintrace.core.validation

import com.github.melancholic.fintrace.core.exception.ValidationError
import com.github.melancholic.fintrace.core.validation.ValidationConstants.CURRENCY_PATTERN
import java.util.*

object CurrencyValidator {

    private val CURRENCY_REGEX = Regex(CURRENCY_PATTERN)

    fun requireKnown(code: String) {
        if (!CURRENCY_REGEX.matches(code)) {
            throw ValidationError("Currency must be a three-letter uppercase ISO-4217 code, was '$code'")
        }
        runCatching { Currency.getInstance(code) }
            .onFailure { throw ValidationError("Unknown currency code '$code'") }
    }
}
