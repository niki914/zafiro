package com.niki914.libterm.backend.shizuku.internal

import com.niki914.shizuku.ShizukuClient

internal interface ShizukuAccessChecker {
    suspend fun checkAccess(): ShizukuAccessState
}

internal sealed interface ShizukuAccessState {
    data object NotInstalledOrNotRunning : ShizukuAccessState
    data object Unauthorized : ShizukuAccessState
    data object Authorized : ShizukuAccessState
}

internal class RealShizukuAccessChecker(
    private val client: ShizukuClient = ShizukuClient.create(),
) : ShizukuAccessChecker {
    override suspend fun checkAccess(): ShizukuAccessState {
        return when {
            !client.isBinderAlive -> ShizukuAccessState.NotInstalledOrNotRunning
            !client.isAuthorized -> ShizukuAccessState.Unauthorized
            else -> ShizukuAccessState.Authorized
        }
    }
}
