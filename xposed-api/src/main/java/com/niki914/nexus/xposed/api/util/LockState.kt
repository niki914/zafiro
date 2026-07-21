package com.niki914.nexus.xposed.api.util

import android.app.KeyguardManager
import android.os.UserManager
import kotlinx.coroutines.withTimeoutOrNull

object LockState {

    suspend fun isUnlocked(): Boolean = withTimeoutOrNull(1000L) {
        val context = ContextProvider.await()
        val keyguardManager = context.getSystemService(KeyguardManager::class.java)
            ?: return@withTimeoutOrNull false
        val userManager = context.getSystemService(UserManager::class.java)
            ?: return@withTimeoutOrNull false

        if (!userManager.isUserUnlocked) return@withTimeoutOrNull false
        return@withTimeoutOrNull !keyguardManager.isDeviceLocked
    } ?: false
}