package com.niki914.permission

import com.niki914.logging.Backend
import com.niki914.logging.Level
import com.niki914.logging.Logger
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * shell 授权命令的构造与结果判定。命令文本被系统侧解析，写错只会静默失败，
 * 因此这里钉住命令内容与三种收尾状态（GRANTED / DENIED_BY_USER / FAILED）。
 */
class ShellGrantsTest {

    /** JVM 单测没有 Android 框架，默认的 LogcatBackend 会在业务代码打日志时抛异常。 */
    @BeforeTest
    fun silenceLogger() {
        Logger.install(object : Backend {
            override fun emit(level: Level, tag: String, msg: String, throwable: Throwable?) = Unit
        })
    }

    @Test
    fun `grantNotification records pm grant and appops commands and succeeds on verified grant`() = runBlocking {
        val commands = mutableListOf<String>()
        var verifyCalls = 0

        val state = ShellGrants.grantNotification(
            run = { command ->
                commands += command
                ShellOutcome(exitCode = 0)
            },
            packageName = "com.niki914.zafiro",
            verify = {
                verifyCalls++
                PermissionState.GRANTED
            },
        )

        assertEquals(PermissionState.GRANTED, state)
        // 第一条命令后复查即已授权，不需要再跑 appops
        assertEquals(listOf("pm grant com.niki914.zafiro android.permission.POST_NOTIFICATIONS"), commands)
        assertEquals(1, verifyCalls)
    }

    @Test
    fun `grantNotification falls through to appops when pm grant does not take effect`() = runBlocking {
        val commands = mutableListOf<String>()

        val state = ShellGrants.grantNotification(
            run = { command ->
                commands += command
                ShellOutcome(exitCode = 0)
            },
            packageName = "com.niki914.zafiro",
            // 第一条命令后系统仍报未授权，第二条后生效
            verify = { if (commands.size >= 2) PermissionState.GRANTED else PermissionState.DENIED_BY_USER },
        )

        assertEquals(PermissionState.GRANTED, state)
        assertEquals(2, commands.size)
        assertTrue(commands[1].startsWith("appops set com.niki914.zafiro POST_NOTIFICATION"))
    }

    @Test
    fun `grantNotification reports denied when commands run but permission stays denied`() = runBlocking {
        val state = ShellGrants.grantNotification(
            run = { ShellOutcome(exitCode = 0) },
            packageName = "com.niki914.zafiro",
            verify = { PermissionState.DENIED_BY_USER },
        )

        // 链靠 DENIED_BY_USER 继续降级到系统弹窗；报 FAILED 会掩盖“命令被接受”这个事实
        assertEquals(PermissionState.DENIED_BY_USER, state)
    }

    @Test
    fun `grantNotification reports failed when no command ran`() = runBlocking {
        val state = ShellGrants.grantNotification(
            run = { ShellOutcome(exitCode = 1) },
            packageName = "com.niki914.zafiro",
            verify = { PermissionState.DENIED_BY_USER },
        )

        assertEquals(PermissionState.FAILED, state)
    }

    @Test
    fun `grantNotification skips commands whose process could not be created`() = runBlocking {
        val commands = mutableListOf<String>()

        val state = ShellGrants.grantNotification(
            run = { command ->
                commands += command
                null
            },
            packageName = "com.niki914.zafiro",
            verify = { PermissionState.DENIED_BY_USER },
        )

        assertEquals(PermissionState.FAILED, state)
        assertEquals(2, commands.size)
    }
}
