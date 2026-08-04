package com.niki914.libterm.runtime

import com.niki914.libterm.AuthorizationMode
import com.niki914.libterm.SshOpenOptions
import com.niki914.libterm.TerminalIdentity

class TerminalOpenSpec {
    lateinit var identity: TerminalIdentity
    var authorizationMode: AuthorizationMode? = null
    var cwd: String? = null
    internal var sshOptions: SshOpenOptions? = null

    fun ssh(configure: SshOpenSpec.() -> Unit) {
        sshOptions = SshOpenSpec().apply(configure).toOpenOptions()
    }

    internal fun hasIdentity(): Boolean = ::identity.isInitialized
}
