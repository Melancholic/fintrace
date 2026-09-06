package com.github.melancholic.fintrace.core.api.v1.dto

import com.github.melancholic.fintrace.core.validation.ValidationConstants.ACCOUNT_NAME_PATTERN
import com.github.melancholic.fintrace.core.validation.ValidationConstants.CURRENCY_PATTERN
import com.github.melancholic.fintrace.core.validation.ValidationConstants.MAX_ICON_LENGTH
import com.github.melancholic.fintrace.core.validation.ValidationConstants.MAX_NAME_LENGTH
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.hibernate.validator.constraints.Length
import java.time.LocalDateTime
import java.util.*

data class CreateAccountRequest(
    @Length(max = MAX_NAME_LENGTH)
    @NotBlank
    @Pattern(regexp = ACCOUNT_NAME_PATTERN)
    val name: String,
    @NotBlank
    @Pattern(regexp = CURRENCY_PATTERN)
    val currency: String,
    @Length(max = MAX_ICON_LENGTH)
    val icon: String?
)

data class UpdateAccountRequest(
    @Length(max = MAX_NAME_LENGTH)
    @NotBlank
    @Pattern(regexp = ACCOUNT_NAME_PATTERN)
    val name: String,
    @Length(max = MAX_ICON_LENGTH)
    val icon: String?
)

data class AccountResponse(
    val id: UUID,
    val workspaceId: UUID,
    val name: String,
    val currency: String,
    val archived: Boolean,
    val icon: String?,
    val recordedAt: LocalDateTime
)
