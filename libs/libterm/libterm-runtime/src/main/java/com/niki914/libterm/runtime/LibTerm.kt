package com.niki914.libterm.runtime

import android.content.Context
import com.niki914.libterm.AuthorizationMode
import com.niki914.libterm.SshAuth
import com.niki914.libterm.SshOpenOptions
import com.niki914.libterm.TerminalBackend
import com.niki914.libterm.TerminalBufferConfig
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.TerminalManager
import com.niki914.libterm.TerminalOpenOptions
import com.niki914.libterm.backend.libsu.LibsuPrivilegeProvider
import com.niki914.libterm.backend.libsu.LibsuTerminalBackend
import com.niki914.libterm.backend.shizuku.ShizukuPrivilegeProvider
import com.niki914.libterm.backend.ssh.SshTerminalBackend
import com.niki914.libterm.runtime.internal.DefaultTerm
import com.niki914.libterm.runtime.internal.RuntimeBackendFactory
import com.niki914.libterm.runtime.internal.RuntimeClock
import com.niki914.libterm.runtime.internal.RuntimeIdGenerator
import com.niki914.libterm.runtime.internal.RuntimePrivilegeAuthorizer
import com.niki914.libterm.runtime.internal.RuntimePrivilegeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object LibTerm {
    fun runtime(
        context: Context? = null,
        scope: CoroutineScope? = null,
        bufferConfig: TerminalBufferConfig = TerminalBufferConfig(),
        configure: LibTermRuntimeConfig.() -> Unit = {},
    ): LibTermRuntime {
        val runtimeScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return LibTermRuntime(
            manager = createRuntimeManager(
                context = context,
                scope = runtimeScope,
                bufferConfig = bufferConfig,
            ),
            config = LibTermRuntimeConfig().apply(configure),
        )
    }

    fun openUserTerm(
        cwd: String? = null,
    ): Term {
        return createTerm(
            context = null,
            identity = TerminalIdentity.User,
            openOptions = TerminalOpenOptions(cwd = cwd),
        )
    }

    fun openSuTerm(
        cwd: String? = null,
    ): Term {
        return createTerm(
            context = null,
            identity = TerminalIdentity.Su,
            openOptions = TerminalOpenOptions(cwd = cwd),
        )
    }

    fun openShizukuTerm(
        context: Context,
        cwd: String? = null,
    ): Term {
        return createTerm(
            context = context,
            identity = TerminalIdentity.Shizuku,
            openOptions = TerminalOpenOptions(cwd = cwd),
        )
    }

    fun openSshTerm(
        host: String,
        port: Int = SshOpenOptions.DEFAULT_PORT,
        username: String,
        password: String,
    ): Term {
        return createTerm(
            context = null,
            identity = TerminalIdentity.Ssh,
            openOptions = TerminalOpenOptions(
                ssh = SshOpenOptions(
                    host = host,
                    port = port,
                    username = username,
                    auth = SshAuth.Password(password),
                ),
            ),
        )
    }

    private fun createTerm(
        context: Context?,
        identity: TerminalIdentity,
        openOptions: TerminalOpenOptions,
    ): Term {
        val termScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return DefaultTerm(
            runtime = LibTermRuntime(
                manager = createRuntimeManager(
                    context = context,
                    scope = termScope,
                    bufferConfig = TerminalBufferConfig(),
                ),
            ),
            identity = identity,
            authorizationMode = AuthorizationMode.REQUEST_IF_NEEDED,
            openOptions = openOptions,
            scope = termScope,
            ownsScope = true,
        )
    }

    private fun createRuntimeManager(
        context: Context?,
        scope: CoroutineScope,
        bufferConfig: TerminalBufferConfig,
    ): TerminalManager {
        val appContext = context?.applicationContext ?: context
        val clock = RuntimeClock
        val libsuProvider = LibsuPrivilegeProvider()
        val shizukuProvider = ShizukuPrivilegeProvider()
        val backendFactory: (TerminalIdentity, TerminalOpenOptions) -> TerminalBackend =
            if (appContext != null) {
                RuntimeBackendFactory(
                    context = appContext,
                    clock = clock,
                    scope = scope,
                )::create
            } else {
                { identity, openOptions ->
                    when (identity) {
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

                        TerminalIdentity.Shizuku -> throw IllegalStateException(
                            "Context is required for Shizuku sessions",
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

        return TerminalManager(
            privilegeProvider = RuntimePrivilegeProvider(
                libsuProvider = libsuProvider,
                shizukuProvider = shizukuProvider,
            ),
            privilegeAuthorizer = RuntimePrivilegeAuthorizer(
                libsuProvider = libsuProvider,
            ),
            idGenerator = RuntimeIdGenerator(),
            clock = clock,
            scope = scope,
            backendFactory = backendFactory,
            bufferConfig = bufferConfig,
        )
    }
}
