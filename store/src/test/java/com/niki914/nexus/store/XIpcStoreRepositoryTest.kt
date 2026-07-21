package com.niki914.nexus.store

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class XIpcStoreRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun missingFileReturnsDescriptorDefaultJson() = runTest {
        val context = testContext()

        val json = XIpcStoreRepository.readJson(context, StoreDescriptorRegistry.AGENT_MAIN_MEMORY_ID)

        assertEquals("""{"memories":[]}""", json)
    }

    @Test
    fun blankFileReturnsDescriptorDefaultJson() = runTest {
        val context = testContext()
        val descriptor = StoreDescriptorRegistry.require(StoreDescriptorRegistry.TOOLS_CUSTOM_ID)
        ConfigPersistence.fileFor(context, descriptor).writeUtf8("   ")

        val json = XIpcStoreRepository.readJson(context, StoreDescriptorRegistry.TOOLS_CUSTOM_ID)

        assertEquals("""{"tools":[]}""", json)
    }

    @Test
    fun brokenJsonReturnsDescriptorDefaultJsonOnRead() = runTest {
        val context = testContext()
        val descriptor = StoreDescriptorRegistry.require(StoreDescriptorRegistry.TOOLS_MCP_SERVERS_ID)
        ConfigPersistence.fileFor(context, descriptor).writeUtf8("{broken")

        val json = XIpcStoreRepository.readJson(context, StoreDescriptorRegistry.TOOLS_MCP_SERVERS_ID)

        assertEquals("""{"servers":[]}""", json)
    }

    @Test
    fun unknownStoreIdDoesNotCreateFile() = runTest {
        val context = testContext()

        val json = XIpcStoreRepository.readJson(context, "unknown.store")
        val error = try {
            XIpcStoreRepository.mutateJson(context, "unknown.store", "enabled", "true")
            null
        } catch (e: IllegalArgumentException) {
            e
        }

        assertEquals("{}", json)
        assertNotNull(error)
        assertFalse(context.filesDir.walkTopDown().any { it.isFile })
    }

    @Test
    fun mutateWritesOnlyTargetStoreFile() = runTest {
        val context = testContext()
        val targetDescriptor = StoreDescriptorRegistry.require(StoreDescriptorRegistry.TOOLS_BUILTIN_ID)
        val otherDescriptor = StoreDescriptorRegistry.require(StoreDescriptorRegistry.TOOLS_CUSTOM_ID)
        val otherFile = ConfigPersistence.fileFor(context, otherDescriptor)
        otherFile.writeUtf8("""{"tools":[]}""")

        val updated = XIpcStoreRepository.mutateJson(
            context = context,
            storeId = StoreDescriptorRegistry.TOOLS_BUILTIN_ID,
            path = "enabled_for_agents.launch_app",
            valueJson = """["main"]"""
        )

        val targetFile = ConfigPersistence.fileFor(context, targetDescriptor)
        assertTrue(targetFile.exists())
        assertEquals(updated, targetFile.readText())
        assertEquals(listOf("main"), JSONObject(updated)
            .getJSONObject("enabled_for_agents")
            .getJSONArray("launch_app")
            .let { array -> List(array.length()) { index -> array.getString(index) } })
        assertEquals("""{"tools":[]}""", otherFile.readText())
    }

    private fun testContext(): Context {
        val filesDir = temporaryFolder.newFolder()
        return object : ContextWrapper(null) {
            override fun getFilesDir(): File = filesDir
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = "com.niki914.nexus.agentic"
        }
    }

    private fun File.writeUtf8(text: String) {
        parentFile?.mkdirs()
        writeText(text, Charsets.UTF_8)
    }
}
