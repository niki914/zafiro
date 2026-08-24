package com.niki914.nexus.store

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
        val descriptor =
            StoreDescriptorRegistry.resolveDynamic(StoreDescriptorRegistry.WEB_SETTINGS_ID)

        assertEquals(StoreDescriptorRegistry.WEB_SETTINGS_ID, descriptor!!.id)
        assertEquals("settings/hooks.json", descriptor.relativePath)
    }
}
