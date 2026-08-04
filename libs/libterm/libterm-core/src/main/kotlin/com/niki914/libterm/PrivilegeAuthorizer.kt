package com.niki914.libterm

interface PrivilegeAuthorizer {
    suspend fun requestAuthorization(identity: TerminalIdentity): AuthorizationResult
}
