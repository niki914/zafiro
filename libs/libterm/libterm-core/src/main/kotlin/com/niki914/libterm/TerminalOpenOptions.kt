package com.niki914.libterm

data class TerminalOpenOptions(
    val cwd: String? = null,
    val ssh: SshOpenOptions? = null,
)
