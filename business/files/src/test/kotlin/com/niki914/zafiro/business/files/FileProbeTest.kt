package com.niki914.zafiro.business.files

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** 可达性探测的四种情况。用临时目录造真实文件系统状态，不起 Android 框架。 */
class FileProbeTest {

    @Test
    fun `readable file is reachable`() {
        val root = Files.createTempDirectory("zfr-probe").toFile()
        val file = File(root, "report.pdf").apply { writeText("x") }
        assertEquals(FileReachability.Reachable, FileProbe.probe(file.path))
    }

    @Test
    fun `listable directory is reachable`() {
        val root = Files.createTempDirectory("zfr-probe").toFile()
        val dir = File(root, "logs").apply { mkdirs() }
        assertEquals(FileReachability.Reachable, FileProbe.probe(dir.path))
    }

    @Test
    fun `absent path under a listable parent is missing`() {
        val root = Files.createTempDirectory("zfr-probe").toFile()
        assertEquals(FileReachability.Missing, FileProbe.probe(File(root, "nope.pdf").path))
    }

    @Test
    fun `unlistable parent reports permission denied`() {
        val root = Files.createTempDirectory("zfr-probe").toFile()
        val locked = File(root, "locked").apply { mkdirs() }
        val target = File(locked, "inside.pdf").apply { writeText("x") }
        locked.setReadable(false, false)
        locked.setExecutable(false, false)
        try {
            if (locked.list() != null) {
                // 以 root 跑测试时 chmod 拦不住（CI 容器里常见），这个断言没有意义
                return
            }
            assertEquals(FileReachability.PermissionDenied, FileProbe.probe(target.path))
        } finally {
            locked.setExecutable(true, false)
            locked.setReadable(true, false)
            root.deleteRecursively()
        }
    }
}
