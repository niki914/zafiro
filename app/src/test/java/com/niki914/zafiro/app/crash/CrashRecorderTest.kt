// 保护：CrashRecorder.peekPendingCrash 必须完成 read-and-delete 消费契约，且在畸形内容下安全降级不引发冷启动崩溃。
package com.niki914.zafiro.app.crash

import android.content.Context
import android.content.ContextWrapper
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

    @Test
    fun peekPendingCrash_consumesAndDeletesPendingReport() {
        CrashRecorder.handleUncaughtException(
            context = context,
            thread = Thread.currentThread(),
            throwable = IllegalStateException("Test crash message"),
        )

        val crashFile = File(filesDir, CrashRecorder.CRASH_FILE_NAME)
        assertTrue(crashFile.exists())

        val report = CrashRecorder.peekPendingCrash(context)
        assertNotNull(report)
        assertEquals("java.lang.IllegalStateException", report?.exceptionType)
        assertEquals("Test crash message", report?.message)

        assertFalse("File must be deleted after peek to avoid duplicate prompts", crashFile.exists())
        assertNull("Subsequent peek must return null", CrashRecorder.peekPendingCrash(context))
    }

    @Test
    fun peekPendingCrash_recoversGracefullyFromMalformedJson() {
        val crashFile = File(filesDir, CrashRecorder.CRASH_FILE_NAME)
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
}
