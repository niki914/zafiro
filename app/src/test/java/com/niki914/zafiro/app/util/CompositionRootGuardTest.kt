package com.niki914.zafiro.app.util

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 组合根纪律守卫（源码扫描，和 PermissionEntryGuardTest 同一family）：
 *
 * - `AppServices` 只做登记，不去 registry 取自己刚装的东西。实现侧要协作者就自己
 *   `requireService()`（见 AppServices KDoc 与 AgentRuntimeService 的写法）。
 * - `installService` 只出现在组合根。多一个装配点 = "谁依赖谁"的清单裂成两份。
 *
 * 这不是编译器级别的约束，只是一个便宜的回归网：一旦有人在组合根里写
 * `createXxx(requireService(), ...)`，测试立刻红，而不是半年后没人说得清装配顺序。
 */
class CompositionRootGuardTest {

    @Test
    fun `composition root only registers, never resolves`() {
        val text = compositionRoot().readText()
        assertTrue(
            "AppServices 里不允许出现 requireService：实现自己取协作者，" +
                "组合根只负责 installService 登记（否则装配顺序会变成隐式契约）",
            "requireService" !in text,
        )
    }

    @Test
    fun `installService appears only in the composition root`() {
        val violations = mainSourceFiles()
            .filter { "installService" in it.readText() }
            .map { it.relativeTo(findRepoRoot()).path }
            .filterNot { it.endsWith("app/src/main/java/com/niki914/zafiro/app/AppServices.kt") }
            // 注册表自身的定义（business:api）不算装配点
            .filterNot { it.contains("business/api/") }
        assertTrue(
            "installService 只允许出现在组合根 AppServices.kt：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    private fun compositionRoot(): File =
        File(findRepoRoot(), "app/src/main/java/com/niki914/zafiro/app/AppServices.kt")

    private fun mainSourceFiles(): List<File> =
        findRepoRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { "/src/main/" in it.path }
            .filterNot { "/build/" in it.path }
            .toList()

    private fun findRepoRoot(): File {
        // 从测试工作目录向上找 settings.gradle.kts
        var dir = File(System.getProperty("user.dir"))
        while (true) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            val parent = dir.parentFile
            if (parent == null) fail("找不到仓库根目录（settings.gradle.kts）")
            dir = parent
        }
    }
}
