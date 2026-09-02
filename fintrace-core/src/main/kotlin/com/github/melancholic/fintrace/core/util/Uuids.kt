package com.github.melancholic.fintrace.core.util

import com.fasterxml.uuid.Generators
import org.springframework.stereotype.Component
import java.util.*

interface UUIDGenerator {
    fun nextUUID(): UUID
}

@Component
class UUIDv7Generator : UUIDGenerator {
    override fun nextUUID(): UUID = generator.generate()

    companion object {
        private val generator = Generators.timeBasedEpochRandomGenerator()
    }
}
