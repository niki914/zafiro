// 保护：CrashRecorder.peekPendingCrash 必须完成 read-and-delete 消费契约，且在畸形内容下安全降级不引发冷启动崩溃；
// consumeStartupCrash 只能消费启动窗口内的崩溃报告——窗口外的报告必须原样留给弹窗，否则现有的崩溃提示会静默消失。
package com.niki914.zafiro.app.crash

import android.content.Context
import android.content.ContextWrapper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashRecorderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val filesDir: File by lazy { temporaryFolder.newFolder("files") }

    private val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = this@CrashRecorderTest.filesDir
    }

    private val crashFile: File by lazy { File(filesDir, CrashRecorder.CRASH_FILE_NAME) }

    private val json = Json { encodeDefaults = false }

    @Test
    fun peekPendingCrash_consumesAndDeletesPendingReport() {
        CrashRecorder.handleUncaughtException(
            context = context,
            thread = Thread.currentThread(),
            throwable = IllegalStateException("Test crash message"),
        )

        assertTrue(crashFile.exists())

        val report = CrashRecorder.peekPendingCrash(context)
        assertNotNull(report)
        assertEquals("java.lang.IllegalStateException", report?.exceptionType)
        assertEquals("Test crash message", report?.message)

        assertFalse(
            "File must be deleted after peek to avoid duplicate prompts",
            crashFile.exists()
        )
        assertNull("Subsequent peek must return null", CrashRecorder.peekPendingCrash(context))
    }

    @Test
    fun peekPendingCrash_recoversGracefullyFromMalformedJson() {
        crashFile.writeText("Corrupted raw dump line 1\nCorrupted raw dump line 2")

        val report = CrashRecorder.peekPendingCrash(context)
        assertNotNull(report)
        assertEquals("RawCrashLog", report?.exceptionType)
        assertTrue(report?.stackTrace?.contains("Corrupted raw dump line 1") == true)
        assertFalse("Corrupted file must still be deleted", crashFile.exists())
    }

    @Test
    fun peekPendingCrash_returnsNullWhenNoCrashFileExists() {
        assertNull(CrashRecorder.peekPendingCrash(context))
    }

    @Test
    fun consumeStartupCrash_consumesReportWrittenWithinStartupWindow() {
        writePendingReport(timeSinceStartupMillis = 1_000L)

        CrashRecorder.consumeStartupCrash(context)

        assertFalse(
            "Startup crash must be consumed so the dialog does not prompt twice",
            crashFile.exists(),
        )
    }

    @Test
    fun consumeStartupCrash_keepsReportForDialogWhenCrashHappenedAfterStartupWindow() {
        writePendingReport(timeSinceStartupMillis = CrashReport.STARTUP_CRASH_WINDOW_MILLIS + 1)

        CrashRecorder.consumeStartupCrash(context)

        assertTrue("Late crash must stay on disk for the dialog path", crashFile.exists())
        assertEquals(
            CrashReport.STARTUP_CRASH_WINDOW_MILLIS + 1,
            CrashRecorder.peekPendingCrash(context)!!.timeSinceStartupMillis,
        )
    }

    @Test
    fun consumeStartupCrash_keepsReportWithoutMeasuredUptime() {
        writePendingReport(timeSinceStartupMillis = null)

        CrashRecorder.consumeStartupCrash(context)

        assertTrue(
            "Reports from older versions carry no uptime and must not be mistaken for startup crashes",
            crashFile.exists(),
        )
    }

    /** [timeSinceStartupMillis] 为 null 时写入不含该字段的报告，覆盖缺少 uptime 的文件。 */
    private fun writePendingReport(timeSinceStartupMillis: Long?) {
        val report = CrashReport(
            timestamp = 0L,
            timeFormatted = "test",
            appVersionName = "1.0",
            appVersionCode = 1L,
            isDebug = true,
            deviceManufacturer = "test",
            deviceModel = "test",
            androidVersion = "test",
            threadName = "test",
            exceptionType = "java.lang.IllegalStateException",
            message = "test",
            stackTrace = "test",
            timeSinceStartupMillis = timeSinceStartupMillis ?: -1L,
        )
        crashFile.writeText(json.encodeToString(report))
    }
}
