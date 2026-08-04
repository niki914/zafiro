package com.niki914.libterm.backend.shizuku.internal

import android.content.Context

internal class RealShizukuShellClientFactory(
    private val context: Context,
) : ShizukuShellClientFactory {
    override suspend fun open(cwd: String): ShizukuShellClient {
        return ShizukuUserServiceClient(context).also { it.connect(cwd) }
    }
}
