package com.niki914.okia.tooling

import com.niki914.okia.fake.RecordingToolExecutor
import com.niki914.okia.fake.localTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 库默认工具注册表测试（T6）：register / find / remove / snapshot 语义。
 * snapshot 返回复制（外部持有不影响内部存储）。
 */
class DefaultToolRegistryTest {

    @Test
    fun registerFindReturnsRegisteredTool() {
        val registry = DefaultToolRegistry()
        val executor = RecordingToolExecutor()
        registry.register(localTool("tool"), executor)

        val found = registry.find("tool")
        assertEquals("tool", found!!.descriptor.name)
        assertEquals(executor, found.executor)
    }

    @Test
    fun removeMakesFindReturnNull() {
        val registry = DefaultToolRegistry()
        registry.register(localTool("tool"), RecordingToolExecutor())
        registry.remove("tool")
        assertNull(registry.find("tool"))
    }

    @Test
    fun reRegisterOverwrites() {
        val registry = DefaultToolRegistry()
        val first = RecordingToolExecutor()
        val second = RecordingToolExecutor()
        registry.register(localTool("tool"), first)
        registry.register(localTool("tool"), second)
        assertEquals(second, registry.find("tool")!!.executor)
    }

    @Test
    fun snapshotReturnsCopyUnaffectedByExternalMutation() {
        val registry = DefaultToolRegistry()
        registry.register(localTool("tool"), RecordingToolExecutor())

        val snapshot = registry.snapshot().toMutableList()
        snapshot.clear() // 外部清空不影响内部
        assertEquals(1, registry.find("tool")?.let { 1 } ?: 0)
        assertEquals(1, registry.snapshot().size)
    }

    @Test
    fun snapshotPreservesRegistrationOrder() {
        val registry = DefaultToolRegistry()
        registry.register(localTool("a"), RecordingToolExecutor())
        registry.register(localTool("b"), RecordingToolExecutor())
        registry.register(localTool("c"), RecordingToolExecutor())
        assertEquals(listOf("a", "b", "c"), registry.snapshot().map { it.descriptor.name })
    }

    @Test
    fun snapshotAfterRemoveOmitsRemoved() {
        val registry = DefaultToolRegistry()
        registry.register(localTool("a"), RecordingToolExecutor())
        registry.register(localTool("b"), RecordingToolExecutor())
        registry.remove("a")
        assertEquals(listOf("b"), registry.snapshot().map { it.descriptor.name })
    }
}
