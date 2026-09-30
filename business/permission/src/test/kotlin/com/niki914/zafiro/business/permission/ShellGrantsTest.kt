package com.niki914.zafiro.business.permission

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
 * 因此这里按机制钉住命令内容与三种收尾状态（GRANTED / DENIED_BY_USER / FAILED）。
 */
class ShellGrantsTest {

    /** JVM 单测没有 Android 框架，默认的 LogcatBackend 会在业务代码打日志时抛异常。 */
    @BeforeTest
    fun silenceLogger() {
        Logger.install(object : Backend {
            override fun emit(level: Level, tag: String, msg: String, throwable: Throwable?) = Unit
        })
    }

    private fun grant(
        mechanism: GrantMechanism,
        verify: () -> PermissionState,
        run: suspend (String) -> ShellOutcome?,
    ): PermissionState = runBlocking {
        ShellGrants.grant(
            mechanism = mechanism,
            run = run,
            packageName = "com.niki914.zafiro",
            accessibilityService = null,
            verify = verify,
        )
    }

    @Test
    fun `appop mechanism sends the appops command and succeeds on verified grant`() {
        val commands = mutableListOf<String>()

        val state = grant(
            mechanism = GrantMechanism.AppOp(PermissionSpec.OP_MANAGE_EXTERNAL_STORAGE),
            verify = { PermissionState.GRANTED },
            run = { command ->
                commands += command
                ShellOutcome(exitCode = 0)
            },
        )

        assertEquals(PermissionState.GRANTED, state)
        assertEquals(listOf("appops set com.niki914.zafiro MANAGE_EXTERNAL_STORAGE allow"), commands)
    }

    @Test
    fun `appop mechanism reports denied when the command ran but the state stays denied`() {
        val state = grant(
            mechanism = GrantMechanism.AppOp("MANAGE_EXTERNAL_STORAGE"),
            // 退出码为 0 但系统里仍没授权：链必须靠 DENIED_BY_USER 继续降级，报 FAILED 会掉链
            verify = { PermissionState.DENIED_BY_USER },
            run = { ShellOutcome(exitCode = 0) },
        )

        assertEquals(PermissionState.DENIED_BY_USER, state)
    }

    @Test
    fun `appop mechanism reports failed when the command did not run`() {
        val state = grant(
            mechanism = GrantMechanism.AppOp("MANAGE_EXTERNAL_STORAGE"),
            verify = { PermissionState.DENIED_BY_USER },
            run = { null },
        )

        assertEquals(PermissionState.FAILED, state)
    }

    @Test
    fun `runtime mechanism grants every name and stops once verified`() {
        val commands = mutableListOf<String>()
        var verifyCalls = 0

        val state = grant(
            mechanism = GrantMechanism.Runtime(
                names = listOf(
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.WRITE_EXTERNAL_STORAGE",
                ),
            ),
            verify = {
                verifyCalls++
                PermissionState.GRANTED
            },
            run = { command ->
                commands += command
                ShellOutcome(exitCode = 0)
            },
        )

        assertEquals(PermissionState.GRANTED, state)
        // 第一条命令后复查即已授权（同组权限一起给），不必再跑第二条
        assertEquals(
            listOf("pm grant com.niki914.zafiro android.permission.READ_EXTERNAL_STORAGE"),
            commands,
        )
        assertEquals(1, verifyCalls)
    }

    @Test
    fun `runtime mechanism falls through to the appop name when pm grant does not take effect`() {
        val commands = mutableListOf<String>()

        val state = grant(
            mechanism = GrantMechanism.Runtime(
                names = listOf("android.permission.POST_NOTIFICATIONS"),
                appOps = listOf("POST_NOTIFICATION"),
            ),
            // 第一条命令后系统仍报未授权，第二条后生效
            verify = { if (commands.size >= 2) PermissionState.GRANTED else PermissionState.DENIED_BY_USER },
            run = { command ->
                commands += command
                ShellOutcome(exitCode = 0)
            },
        )

        assertEquals(PermissionState.GRANTED, state)
        assertEquals(2, commands.size)
        assertTrue(commands[1].startsWith("appops set com.niki914.zafiro POST_NOTIFICATION"))
    }

    @Test
    fun `runtime mechanism reports denied when commands run but permission stays denied`() {
        val state = grant(
            mechanism = GrantMechanism.Runtime(names = listOf("android.permission.POST_NOTIFICATIONS")),
            verify = { PermissionState.DENIED_BY_USER },
            run = { ShellOutcome(exitCode = 0) },
        )

        // 链靠 DENIED_BY_USER 继续降级到系统弹窗；报 FAILED 会掩盖“命令被接受”这个事实
        assertEquals(PermissionState.DENIED_BY_USER, state)
    }

    @Test
    fun `runtime mechanism reports failed when no command ran`() {
        val state = grant(
            mechanism = GrantMechanism.Runtime(
                names = listOf("android.permission.POST_NOTIFICATIONS"),
                appOps = listOf("POST_NOTIFICATION"),
            ),
            verify = { PermissionState.DENIED_BY_USER },
            run = { ShellOutcome(exitCode = 1) },
        )

        assertEquals(PermissionState.FAILED, state)
    }

    @Test
    fun `runtime mechanism skips commands whose process could not be created`() {
        val commands = mutableListOf<String>()

        val state = grant(
            mechanism = GrantMechanism.Runtime(
                names = listOf("android.permission.POST_NOTIFICATIONS"),
                appOps = listOf("POST_NOTIFICATION"),
            ),
            verify = { PermissionState.DENIED_BY_USER },
            run = { command ->
                commands += command
                null
            },
        )

        assertEquals(PermissionState.FAILED, state)
        assertEquals(2, commands.size)
    }

    @Test
    fun `none mechanism grants without running anything`() {
        val commands = mutableListOf<String>()

        val state = grant(
            mechanism = GrantMechanism.None,
            verify = { PermissionState.DENIED_BY_USER },
            run = { command ->
                commands += command
                ShellOutcome(exitCode = 0)
            },
        )

        assertEquals(PermissionState.GRANTED, state)
        assertEquals(emptyList(), commands)
    }
}
