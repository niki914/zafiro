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
    fun matchByName_returnsFoundForSingleFuzzyMatch() {
        val result = AppInfoMatcher.matchByName(apps, "设置")

        assertEquals(AppMatchResult.Found(apps[2]), result)
    }

    @Test
    fun matchByName_returnsCandidatesWhenFuzzyMatchIsAmbiguous() {
        val result = AppInfoMatcher.matchByName(apps, "音乐")

        assertTrue(result is AppMatchResult.Candidates)
        assertEquals(
            listOf(apps[3], apps[4]),
            (result as AppMatchResult.Candidates).apps,
        )
    }
}
