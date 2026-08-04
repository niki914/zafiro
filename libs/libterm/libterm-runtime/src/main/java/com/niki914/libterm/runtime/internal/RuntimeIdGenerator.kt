package com.niki914.libterm.runtime.internal

import com.niki914.libterm.IdGenerator
import java.util.UUID

internal class RuntimeIdGenerator : IdGenerator {
    override fun nextId(): String = UUID.randomUUID().toString()
}
