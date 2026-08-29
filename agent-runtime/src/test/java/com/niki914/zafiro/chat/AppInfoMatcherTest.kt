package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.device.AppInfo
import com.niki914.zafiro.chat.agentic.device.AppInfoMatcher
import com.niki914.zafiro.chat.agentic.device.AppMatchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppInfoMatcherTest {
    private val apps = listOf(
        AppInfo(packageName = "com.tencent.mm", appName = "微信", isSystemApp = false),
        AppInfo(packageName = "com.tencent.mobileqq", appName = "QQ", isSystemApp = false),
        AppInfo(packageName = "com.android.settings", appName = "设置", isSystemApp = true),
        AppInfo(packageName = "com.example.music", appName = "音乐播放器", isSystemApp = false),
        AppInfo(packageName = "com.example.music.lite", appName = "轻音乐", isSystemApp = false),
    )

    @Test
    fun matchByName_returnsFoundForExactName() {
        val result = AppInfoMatcher.matchByName(apps, "微信")

        assertEquals(AppMatchResult.Found(apps[0]), result)
    }

    @Test
    fun matchByName_returnsFoundForExactNameInAnyLocale() {
        // 英文系统场景：appName 是英文标签，中文查询经多语言标签精确命中
        val wechat = AppInfo(
            packageName = "com.tencent.mm",
            appName = "WeChat",
            isSystemApp = false,
            labels = setOf("WeChat", "微信"),
        )
        val wetype = AppInfo(
            packageName = "com.tencent.wetype",
            appName = "微信输入法",
            isSystemApp = false,
            labels = setOf("微信输入法"),
        )

        val result = AppInfoMatcher.matchByName(listOf(wechat, wetype), "微信")

        assertEquals(AppMatchResult.Found(wechat), result)
    }

    @Test
    fun matchByName_returnsFoundForSinglePrefixMatch() {
        val result = AppInfoMatcher.matchByName(apps, "音乐播")

        assertEquals(AppMatchResult.Found(apps[3]), result)
    }

    @Test
    fun matchByName_returnsCandidatesWhenPrefixMatchIsAmbiguous() {
        val music = AppInfo(packageName = "com.example.music", appName = "音乐播放器", isSystemApp = false)
        val musicPro = AppInfo(packageName = "com.example.music.pro", appName = "音乐专业版", isSystemApp = false)

        val result = AppInfoMatcher.matchByName(listOf(music, musicPro), "音乐")

        assertTrue(result is AppMatchResult.Candidates)
    }

    @Test
    fun matchByName_returnsCandidatesForSingleContainsOnlyMatch() {
        // 产品族子串：仅包含命中（非精确/前缀）时唯一候选也不直接启动，交由模型裁决
        val wetype = AppInfo(packageName = "com.tencent.wetype", appName = "微信输入法", isSystemApp = false)

        val result = AppInfoMatcher.matchByName(listOf(wetype), "信输入")

        assertEquals(AppMatchResult.Candidates(listOf(wetype)), result)
    }

    @Test
    fun matchByName_prefersExactOverWeakerTiers() {
        val result = AppInfoMatcher.matchByName(apps, "QQ")

        assertEquals(AppMatchResult.Found(apps[1]), result)
    }

    @Test
    fun matchByName_returnsNotFoundWhenNoLabelMatches() {
        val result = AppInfoMatcher.matchByName(apps, "邮箱")

        assertEquals(AppMatchResult.NotFound, result)
    }
}
