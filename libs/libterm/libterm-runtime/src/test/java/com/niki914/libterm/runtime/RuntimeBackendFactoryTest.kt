package com.niki914.libterm.runtime

import android.content.Context
import android.content.ContextWrapper
import com.niki914.libterm.Clock
import com.niki914.libterm.SshAuth
import com.niki914.libterm.SshOpenOptions
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.TerminalOpenOptions
import com.niki914.libterm.runtime.internal.RuntimeBackendFactory
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeBackendFactoryTest {

    @Test
    fun `user create returns user backend without starting it`() {
        val factory = createFactory()

        val backend = factory.create(TerminalIdentity.User, TerminalOpenOptions())

        assertEquals(TerminalIdentity.User, backend.identity)
    }

    @Test
    fun `root create returns root backend without starting it`() {
        val factory = createFactory()

        val backend = factory.create(TerminalIdentity.Su, TerminalOpenOptions())

        assertEquals(TerminalIdentity.Su, backend.identity)
    }

    @Test
    fun `shizuku create returns shizuku backend without starting it`() {
        val factory = createFactory()

        val backend = factory.create(TerminalIdentity.Shizuku, TerminalOpenOptions())

        assertEquals(TerminalIdentity.Shizuku, backend.identity)
    }

    @Test
    fun `ssh create returns ssh backend without starting it`() {
        val factory = createFactory()

        val backend = factory.create(
            identity = TerminalIdentity.Ssh,
            openOptions = TerminalOpenOptions(ssh = sshOptions()),
        )

        assertEquals(TerminalIdentity.Ssh, backend.identity)
    }

    private fun createFactory(): RuntimeBackendFactory {
        return RuntimeBackendFactory(
            context = RuntimeTestContext(),
            clock = FixedClock,
            scope = TestScope(),
        )
    }

    private fun sshOptions(): SshOpenOptions {
        return SshOpenOptions(
            host = "192.168.1.10",
            username = "root",
            auth = SshAuth.Password("password"),
        )
    }

    private object FixedClock : Clock {
        override fun nowMillis(): Long = 100L
    }

    private class RuntimeTestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }
}
