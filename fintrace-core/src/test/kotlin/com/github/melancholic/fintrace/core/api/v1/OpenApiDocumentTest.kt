package com.github.melancholic.fintrace.core.api.v1

import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The generated contract, which M4 will use for frontend type generation (task 3.14).
 * Reachable without authentication so the docs are usable before you have a token.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocumentTest(@Autowired private val mvc: MockMvc) {

	@Test
	fun `serves the OpenAPI document anonymously`() {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.openapi").exists())
	}

	@Test
	fun `documents both endpoint families`() {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/operations'].post.summary").exists())
			.andExpect(
				jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/operations/{operationId}'].get.summary")
					.exists()
			)
			.andExpect(
				jsonPath("$.paths['/admin/api/v1/workspaces/{workspaceId}/replay'].post.summary").exists()
			)
	}

	@Test
	fun `describes the documented response codes`() {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(
				jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/operations'].post.responses.201")
					.exists()
			)
			.andExpect(
				jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/operations/{operationId}'].get.responses.404")
					.exists()
			)
	}

	@Test
	fun `documents every workspace endpoint`() {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(jsonPath("$.paths['/api/v1/workspaces'].post.summary").exists())
			.andExpect(jsonPath("$.paths['/api/v1/workspaces'].get.summary").exists())
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}'].get.summary").exists())
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}'].put.summary").exists())
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}'].delete.summary").exists())
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/archive'].post.summary").exists())
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/archive'].delete.summary").exists())
	}

	@Test
	fun `documents the conflict and version responses`() {
		// A generated client has to know 409 is reachable, or optimistic locking looks like a
		// server error to it.
		mvc.perform(get("/v3/api-docs"))
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}'].put.responses.409").exists())
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}'].delete.responses.409").exists())
			.andExpect(
				jsonPath("$.paths['/api/v1/workspaces/{workspaceId}'].delete.parameters[?(@.name=='version')]")
					.exists()
			)
	}

	@Test
	fun `documents every account endpoint`() {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/accounts'].post.summary").exists())
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/accounts'].get.summary").exists())
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/accounts/{accountId}'].get.summary").exists())
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/accounts/{accountId}'].put.summary").exists())
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/accounts/{accountId}/archive'].post.summary").exists())
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/accounts/{accountId}/archive'].delete.summary").exists())
	}

	@Test
	fun `serves the Swagger UI anonymously`() {
		mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk)
	}
}
