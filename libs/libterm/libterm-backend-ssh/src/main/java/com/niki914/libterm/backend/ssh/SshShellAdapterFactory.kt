package com.niki914.libterm.backend.ssh

import com.niki914.libterm.SshOpenOptions

internal interface SshShellAdapterFactory {
    suspend fun open(options: SshOpenOptions): SshShellAdapter
}
