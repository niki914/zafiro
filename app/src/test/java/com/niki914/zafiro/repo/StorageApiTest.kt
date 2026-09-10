package com.niki914.zafiro.repo

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageApiTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val filesDir: File by lazy { temporaryFolder.newFolder("files") }
    private val cacheDir: File by lazy { temporaryFolder.newFolder("cache") }
    private val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = this@StorageApiTest.filesDir
        override fun getCacheDir(): File = this@StorageApiTest.cacheDir
    }

    @After
    fun tearDown() {
        XRepo.resetForTest()
    }

    @Test
    fun usage_sumsFilesAndTreatsMissingDirAsZero() = runTest {
        XRepo.init(context)
        writeBytes(File(filesDir, "tool_output/a.log"), 100)
        writeBytes(File(filesDir, "tool_output/sub/b.log"), 50)
        writeBytes(File(filesDir, "downloads/x.apk"), 1000)

        val usage = XRepo.storage.usage().associateBy { it.kind }

        assertEquals(150L, usage[StorageKind.ToolOutput]?.bytes)
        assertEquals(0L, usage[StorageKind.ImageCache]?.bytes)
        assertEquals(1000L, usage[StorageKind.Downloads]?.bytes)
        assertEquals(0L, usage[StorageKind.OtherCache]?.bytes)
    }

    @Test
    fun clear_removesChildrenButKeepsDir() = runTest {
        XRepo.init(context)
        writeBytes(File(filesDir, "downloads/a.apk"), 10)
        writeBytes(File(filesDir, "downloads/sub/b.apk"), 20)

        XRepo.storage.clear(StorageKind.Downloads)

        val dir = File(filesDir, "downloads")
        assertTrue(dir.exists())
        assertEquals(0, dir.walkTopDown().count { it.isFile })
        assertEquals(0L, XRepo.storage.usage().associateBy { it.kind }[StorageKind.Downloads]?.bytes)
    }

    @Test
    fun clear_missingDir_isNoop() = runTest {
        XRepo.init(context)

        XRepo.storage.clear(StorageKind.ImageCache)

        assertFalse(File(filesDir, "image_cache").exists())
    }

    private fun writeBytes(file: File, size: Int) {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(size))
    }
}
