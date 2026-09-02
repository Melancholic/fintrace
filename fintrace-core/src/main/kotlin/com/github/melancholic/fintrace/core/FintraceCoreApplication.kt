package com.github.melancholic.fintrace.core

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity

@SpringBootApplication
@EnableWebSecurity
class FintraceCoreApplication

fun main(args: Array<String>) {
	runApplication<FintraceCoreApplication>(*args)
}
