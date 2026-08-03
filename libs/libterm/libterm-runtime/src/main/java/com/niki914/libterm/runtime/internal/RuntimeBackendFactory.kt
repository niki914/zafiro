package com.niki914.libterm.runtime.internal

import android.content.Context
import com.niki914.libterm.Clock
import com.niki914.libterm.TerminalBackend
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.TerminalOpenOptions
import com.niki914.libterm.backend.libsu.LibsuTerminalBackend
import com.niki914.libterm.backend.shizuku.ShizukuTerminalBackend
import com.niki914.libterm.backend.ssh.SshTerminalBackend
import kotlinx.coroutines.CoroutineScope

internal class RuntimeBackendFactory(
    context: Context,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    private val appContext: Context = context.applicationContext ?: context

    internal fun create(
        identity: TerminalIdentity,
        openOptions: TerminalOpenOptions,
    ): TerminalBackend {
        return when (identity) {
            TerminalIdentity.User -> LibsuTerminalBackend(
                identity = TerminalIdentity.User,
                clock = clock,
                scope = scope,
            )

            TerminalIdentity.Su -> LibsuTerminalBackend(
                identity = TerminalIdentity.Su,
                clock = clock,
                scope = scope,
            )

            TerminalIdentity.Shizuku -> ShizukuTerminalBackend(
                context = appContext,
                clock = clock,
                scope = scope,
            )

            TerminalIdentity.Ssh -> SshTerminalBackend(
                options = requireNotNull(openOptions.ssh) {
                    "SSH open options are required"
                },
                clock = clock,
                scope = scope,
            )
        }
    }
}
