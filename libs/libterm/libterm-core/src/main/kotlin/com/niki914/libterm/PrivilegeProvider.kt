package com.niki914.libterm

interface PrivilegeProvider {
    suspend fun getAvailability(identity: TerminalIdentity): BackendAvailability
}
