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
	fun `serves the Swagger UI anonymously`() {
		mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk)
	}
}
