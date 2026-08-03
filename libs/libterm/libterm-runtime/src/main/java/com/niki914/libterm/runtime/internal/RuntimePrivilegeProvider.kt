package com.niki914.libterm.runtime.internal

import com.niki914.libterm.BackendAvailability
import com.niki914.libterm.PrivilegeProvider
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.backend.libsu.LibsuPrivilegeProvider
import com.niki914.libterm.backend.shizuku.ShizukuPrivilegeProvider

internal class RuntimePrivilegeProvider(
    private val libsuProvider: PrivilegeProvider = LibsuPrivilegeProvider(),
    private val shizukuProvider: PrivilegeProvider = ShizukuPrivilegeProvider(),
) : PrivilegeProvider {
    override suspend fun getAvailability(identity: TerminalIdentity): BackendAvailability {
        return when (identity) {
            TerminalIdentity.User,
            TerminalIdentity.Su -> libsuProvider.getAvailability(identity)

            TerminalIdentity.Shizuku -> shizukuProvider.getAvailability(identity)

            TerminalIdentity.Ssh -> BackendAvailability.Available
        }
    }
}
