package com.github.melancholic.fintrace.core

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<FintraceCoreApplication>().with(TestcontainersConfiguration::class).run(*args)
}
