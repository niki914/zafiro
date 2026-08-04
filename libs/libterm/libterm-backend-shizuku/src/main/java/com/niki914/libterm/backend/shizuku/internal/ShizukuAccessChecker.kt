package com.niki914.libterm.backend.shizuku.internal

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

internal interface ShizukuAccessChecker {
    suspend fun checkAccess(): ShizukuAccessState
}

internal sealed interface ShizukuAccessState {
    data object NotInstalledOrNotRunning : ShizukuAccessState
    data object Unauthorized : ShizukuAccessState
    data object Authorized : ShizukuAccessState
}

internal class RealShizukuAccessChecker : ShizukuAccessChecker {
    override suspend fun checkAccess(): ShizukuAccessState {
        return when {
            !Shizuku.pingBinder() -> ShizukuAccessState.NotInstalledOrNotRunning
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> {
                ShizukuAccessState.Unauthorized
            }

            else -> ShizukuAccessState.Authorized
        }
    }
}
