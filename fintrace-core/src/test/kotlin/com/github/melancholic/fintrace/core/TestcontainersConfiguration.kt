package com.github.melancholic.fintrace.core

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	fun postgresContainer(): PostgreSQLContainer {
		// Must track deploy/docker-compose.yml — testing against a different major is how
		// a version-specific defect reaches the NAS unnoticed.
		return PostgreSQLContainer(DockerImageName.parse("postgres:18"))
	}

}
