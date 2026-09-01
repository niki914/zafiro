package com.niki914.libterm.runtime

import com.niki914.libterm.SshAuth
import com.niki914.libterm.SshHostKeyPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SshOpenSpecTest {

    @Test
    fun `password dsl creates ssh open options`() {
        val spec = SshOpenSpec().apply {
            host = "192.168.1.10"
            port = 2222
            username = "root"
            hostKeyPolicy = SshHostKeyPolicy.KnownHostsFile(
                path = "/data/local/tmp/known_hosts",
                strict = false,
            )
            connectTimeoutMillis = 5_000
            serverAliveIntervalMillis = 15_000
            password("secret")
        }

        val options = spec.toOpenOptions()

        assertEquals("192.168.1.10", options.host)
        assertEquals(2222, options.port)
        assertEquals("root", options.username)
        assertEquals(
            SshHostKeyPolicy.KnownHostsFile(
                path = "/data/local/tmp/known_hosts",
                strict = false,
            ),
            options.hostKeyPolicy,
        )
        assertEquals(5_000, options.connectTimeoutMillis)
        assertEquals(15_000, options.serverAliveIntervalMillis)
        assertEquals("secret", assertIs<SshAuth.Password>(options.auth).value)
    }

    @Test
    fun `missing host throws explicit error`() {
        val spec = SshOpenSpec().apply {
            username = "root"
            password("secret")
        }

        val error = assertFailsWith<IllegalArgumentException> {
            spec.toOpenOptions()
        }

        assertEquals("SSH host is required", error.message)
    }

    @Test
    fun `missing username throws explicit error`() {
        val spec = SshOpenSpec().apply {
            host = "192.168.1.10"
            password("secret")
        }

        val error = assertFailsWith<IllegalArgumentException> {
            spec.toOpenOptions()
        }

        assertEquals("SSH username is required", error.message)
    }

    @Test
    fun `missing password throws explicit error`() {
        val spec = SshOpenSpec().apply {
            host = "192.168.1.10"
            username = "root"
        }

        val error = assertFailsWith<IllegalArgumentException> {
            spec.toOpenOptions()
        }

        assertEquals("SSH password is required", error.message)
    }
}
