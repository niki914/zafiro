package com.niki914.nexus.agentic.runtime.settings

sealed class MemoryMutationResult {
    data object Ok : MemoryMutationResult()
    data object NotFound : MemoryMutationResult()
    data object Ambiguous : MemoryMutationResult()
}
