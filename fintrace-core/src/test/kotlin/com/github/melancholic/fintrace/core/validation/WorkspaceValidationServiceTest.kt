package com.github.melancholic.fintrace.core.validation

import com.github.melancholic.fintrace.core.api.v1.dto.CreateWorkspaceRequest
import com.github.melancholic.fintrace.core.api.v1.dto.EditWorkspaceRequest
import com.github.melancholic.fintrace.core.exception.ValidationError
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * Rules that hold whoever the caller is, so they are tested without Spring, HTTP or a database —
 * the same shape as `OperationValidationServiceTest`.
 */
class WorkspaceValidationServiceTest {

	private val validation = WorkspaceValidationServiceImpl()

	@Test
	fun `accepts a well-formed creation`() {
		validation.validate(CreateWorkspaceRequest("budget-2026", "EUR"))
	}

	@Test
	fun `rejects a blank name`() {
		assertFailsWith<ValidationError> { validation.validate(CreateWorkspaceRequest("   ", "EUR")) }
	}

	@Test
	fun `rejects a name longer than the column`() {
		val tooLong = "a".repeat(ValidationConstants.MAX_NAME_LENGTH + 1)

		// varchar(60) would truncate silently in some databases; Postgres errors. Either way the
		// rule belongs here rather than being discovered at the driver.
		assertFailsWith<ValidationError> { validation.validate(CreateWorkspaceRequest(tooLong, "EUR")) }
	}

	@Test
	fun `rejects a name with characters outside the pattern`() {
		listOf("bad name", "-leading-dash", "semi;colon", "quote'd").forEach {
			assertFailsWith<ValidationError>("expected '$it' to be rejected") {
				validation.validate(CreateWorkspaceRequest(it, "EUR"))
			}
		}
	}

	@Test
	fun `rejects a currency of the wrong shape`() {
		listOf("eur", "EURO", "EU", "E1R", "").forEach {
			assertFailsWith<ValidationError>("expected '$it' to be rejected") {
				validation.validate(CreateWorkspaceRequest("budget", it))
			}
		}
	}

	@Test
	fun `rejects a currency that is well-shaped but does not exist`() {
		// The point of the service-side check: a regex cannot tell ZZZ from EUR.
		assertFailsWith<ValidationError> { validation.validate(CreateWorkspaceRequest("budget", "ZZZ")) }
	}

	@Test
	fun `accepts every currency the runtime knows`() {
		listOf("EUR", "USD", "GBP", "CZK", "JPY").forEach {
			validation.validate(CreateWorkspaceRequest("budget", it))
		}
	}

	@Test
	fun `accepts an edit that changes nothing but the version`() {
		validation.validate(EditWorkspaceRequest(version = 3, workspaceName = null, defaultCurrency = null))
	}

	@Test
	fun `checks only the fields an edit actually carries`() {
		// An absent field means "leave it alone", so it must not be validated as if it were blank.
		validation.validate(EditWorkspaceRequest(version = 0, workspaceName = "renamed", defaultCurrency = null))
		validation.validate(EditWorkspaceRequest(version = 0, workspaceName = null, defaultCurrency = "USD"))
	}

	@Test
	fun `rejects an edit carrying an invalid currency`() {
		assertFailsWith<ValidationError> {
			validation.validate(EditWorkspaceRequest(version = 0, workspaceName = null, defaultCurrency = "ZZZ"))
		}
	}

	@Test
	fun `rejects an edit carrying an invalid name`() {
		assertFailsWith<ValidationError> {
			validation.validate(EditWorkspaceRequest(version = 0, workspaceName = "bad name", defaultCurrency = null))
		}
	}

	@Test
	fun `rejects a negative version`() {
		assertFailsWith<ValidationError> {
			validation.validate(EditWorkspaceRequest(version = -1, workspaceName = "renamed", defaultCurrency = null))
		}
	}
}
