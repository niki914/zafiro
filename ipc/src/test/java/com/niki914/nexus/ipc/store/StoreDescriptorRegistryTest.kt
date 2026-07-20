package com.niki914.nexus.ipc.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreDescriptorRegistryTest {

    @Test
    fun unknownStoreIdReturnsNull() {
        assertNull(StoreDescriptorRegistry.find("unknown.store"))
        assertNull(StoreDescriptorRegistry.resolveDynamic("unknown.store"))
    }

    @Test
    fun legacyLocalSettingsStoreIdResolvesForProviderFileUri() {
        val descriptor = StoreDescriptorRegistry.resolveDynamic("local_settings")

        assertEquals("local_settings", descriptor!!.id)
        assertEquals("local_settings.json", descriptor.relativePath)
    }

    @Test
    fun webSettingsStoreResolvesUnderSettingsHooksJson() {
        val descriptor = StoreDescriptorRegistry.resolveDynamic(StoreDescriptorRegistry.WEB_SETTINGS_ID)

        assertEquals(StoreDescriptorRegistry.WEB_SETTINGS_ID, descriptor!!.id)
        assertEquals("settings/hooks.json", descriptor.relativePath)
    }

    @Test
    fun mcpCacheRejectsPathTraversalServerId() {
        assertNull(StoreDescriptorRegistry.mcpCacheStoreId("../bad"))
        assertNull(StoreDescriptorRegistry.resolveDynamic("tools.mcp.cache.../bad"))
    }

    @Test
    fun mcpCacheRejectsBlankServerId() {
        assertNull(StoreDescriptorRegistry.mcpCacheStoreId(""))
        assertNull(StoreDescriptorRegistry.mcpCacheStoreId(" "))
    }

    @Test
    fun mcpCacheRejectsSlashServerId() {
        assertNull(StoreDescriptorRegistry.mcpCacheStoreId("bad/id"))
        assertNull(StoreDescriptorRegistry.resolveDynamic("tools.mcp.cache.bad/id"))
    }

    @Test
    fun mcpCacheRejectsOverlongServerId() {
        val serverId = "a".repeat(65)

        assertNull(StoreDescriptorRegistry.mcpCacheStoreId(serverId))
    }

    @Test
    fun mcpCacheAcceptsSafeServerId() {
        val storeId = StoreDescriptorRegistry.mcpCacheStoreId("filesystem_1")

        assertEquals("tools.mcp.cache.filesystem_1", storeId)
        val descriptor = StoreDescriptorRegistry.resolveDynamic(storeId!!)
        assertEquals("settings/tools/mcp/cache/filesystem_1.json", descriptor!!.relativePath)
        assertTrue(descriptor.defaultJson.contains("filesystem_1"))
    }
}
