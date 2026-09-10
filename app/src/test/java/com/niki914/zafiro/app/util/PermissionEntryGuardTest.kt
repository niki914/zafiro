package com.niki914.zafiro.app.util

import com.niki914.zafiro.runtime.service.AgentRuntimeService
import android.app.Application
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 权限入口统一守卫：业务代码禁止直连原生权限 API。
 *
 * - 允许名单：PermissionManager 门面 + permission-manager 模块内部 + TargetStatus 静默查询的
 *   被调用方（SystemDialogHandler 弹窗、TargetStatus 查询本身）+ libterm（独立演进的终端运行时）。
 * - 私调清单：checkSelfPermission / requestPermissions / 裸 su / settings put / appops set /
 *   Shizuku.requestPermission / libsu Shell / canDrawOverlays 等，出现在允许名单之外即失败。
 * - 图片/文件选择器（SkillsSettingsContent、HomePageContent）与通知 launcher 用的是
 *   ActivityResultContracts 非权限 contract，不在私调清单内。
 *
 * 新增私调时先改需求：要么收编进 PermissionManager，要么把用例加进白名单并在 PR 里说明。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PermissionEntryGuardTest {

    /** 私调用法 → 允许出现的源码位置（文件后缀 + 行内标记）。 */
    private data class AllowRule(val fileSuffix: String, val marker: String)

    /** 私调用法 → 允许名单。marker 为空表示整文件允许。 */
    private val allowlist: Map<String, List<AllowRule>> = mapOf(
        // 只读查询只经过 TargetStatus；permission-manager 内部实现
        "canDrawOverlays(" to listOf(
            AllowRule("permission/TargetStatus.kt", ""),
        ),
        "checkSelfPermission(" to listOf(
            AllowRule("permission/TargetStatus.kt", ""),
            AllowRule("permission/ShizukuHandler.kt", "Shizuku.checkSelfPermission"),
        ),
        // 系统弹窗的 launcher 调用 + Manifest 文本
        "POST_NOTIFICATIONS" to listOf(
            AllowRule("permission/UiHandlers.kt", ""),
            AllowRule("permission/TargetStatus.kt", ""),
            AllowRule("MainActivity.kt", "RequestPermission"),
            AllowRule("AndroidManifest.xml", ""),
        ),
        // seed py 脚本是 py 工具层自身能力（py 进程无 PermissionManager 可用），显式排除；
        // RootUtils 是 xposed-api 遗留死代码（零调用方），见下方单独断言
        "\"su\"" to listOf(
            AllowRule("seed_py_launch_wechat.py", ""),
            AllowRule("seed_py_install_apk.py", ""),
        ),
        // shell 通道授权命令只出现在 permission-manager 内部
        "settings put secure" to listOf(
            AllowRule("permission/ShellGrants.kt", ""),
        ),
        "appops set" to listOf(
            AllowRule("permission/ShellGrants.kt", ""),
        ),
        "Shizuku.requestPermission" to listOf(
            AllowRule("permission/ShizukuHandler.kt", ""),
        ),
    )

    /** 允许名单之外的任何命中都算私调。扫描范围：全仓 main 源码（build/ 与测试除外）。 */
    private val privateCallPatterns = allowlist.keys +
        "requestPermissions(" +
        "Runtime.getRuntime().exec(" +
        "Settings.Secure.putString" +
        "Shell.getShell(" +
        "Shell.cmd(" +
        "Shell.isAppGrantedRoot" +
        "com.topjohnwu.superuser" +
        "rikka.shizuku" +
        "Shizuku.requestPermission"

    @Test
    fun `no private permission calls outside allowlist`() {
        val repoRoot = findRepoRoot()
        val violations = mutableListOf<String>()
        repoRoot.walkTopDown()
            .filter { it.isFile }
            .filter { it.extension in setOf("kt", "java", "py", "xml") }
            .filter { "src/main/" in it.path || "src/main" in it.path || it.name == "AndroidManifest.xml" }
            .filterNot { "/build/" in it.path }
            .forEach { file ->
                val relative = file.relativeTo(repoRoot).path
                file.readLines().forEachIndexed { index, line ->
                    for (pattern in privateCallPatterns) {
                        if (pattern !in line) continue
                        if (isAllowed(relative, line, pattern)) continue
                        violations += "$relative:${index + 1}: [$pattern] $line".trim()
                    }
                }
            }
        assertTrue(
            "业务方直连原生权限 API（收编进 PermissionManager 或加白名单并在 PR 说明）：\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `RootUtils legacy su helper stays deleted`() {
        // xposed-api 遗留裸 su 工具已删除（零调用方）；一旦有人重建，先收编进 PermissionManager。
        // 引用扫描只看 Kotlin/Java 源码（py 脚本调不到它），本测试文件自身除外。
        val repoRoot = findRepoRoot()
        val self = "PermissionEntryGuardTest.kt"
        val hits = mutableListOf<String>()
        repoRoot.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .filterNot { "/build/" in it.path }
            .filterNot { it.name == self }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if ("RootUtils" in line) {
                        hits += "${file.relativeTo(repoRoot).path}:${index + 1}"
                    }
                }
            }
        assertTrue(
            "RootUtils 被重建或引用了（收编进 PermissionManager 后再用）：\n" + hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    @Test
    fun `service notification gate goes through PermissionManager`() {
        // AgentRuntimeService 的通知门必须经门面：删掉 import 即删掉调用，无调用也要有门面引用。
        val service = File(
            findRepoRoot(),
            "app/src/main/java/com/niki914/zafiro/runtime/service/AgentRuntimeService.kt",
        )
        val text = service.readText()
        assertTrue(
            "AgentRuntimeService 必须经 PermissionManager 查通知状态",
            "PermissionHolder" in text && "targetStatus" in text,
        )
        assertTrue(
            " AgentRuntimeService 禁止直连 checkSelfPermission",
            "checkSelfPermission" !in text,
        )
        assertTrue("AgentRuntimeService 禁止裸 su", "\"su\"" !in text)
    }

    private fun isAllowed(relative: String, line: String, pattern: String): Boolean {
        // libterm 是独立演进的终端运行时，整目录豁免
        if ("/libterm/" in relative || relative.startsWith("libs/libterm/")) return true
        // permission-manager 内部实现就是被收编的正主（含 KDoc 里的方法名引用），整模块豁免
        if (relative.startsWith("libs/permission-manager/src/main/")) return true
        // 测试源码不在扫描范围（walk 已过滤），此处仅防漏网
        if ("/src/test/" in relative) return true
        return allowlist[pattern].orEmpty().any { rule ->
            relative.endsWith(rule.fileSuffix) &&
                (rule.marker.isEmpty() || rule.marker in line)
        }
    }

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
