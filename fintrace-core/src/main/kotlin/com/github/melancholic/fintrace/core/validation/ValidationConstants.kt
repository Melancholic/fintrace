package com.github.melancholic.fintrace.core.validation

object ValidationConstants {
    const val MAX_NAME_LENGTH = 30
    const val MAX_ICON_LENGTH = 30
    const val NAME_PATTERN = "[a-zA-Z0-9][a-zA-Z0-9\\-_\\[\\]()]*"
    const val WORKSPACE_NAME_PATTERN = NAME_PATTERN
    const val ACCOUNT_NAME_PATTERN = NAME_PATTERN
    const val CURRENCY_PATTERN = "[A-Z]{3}"
}