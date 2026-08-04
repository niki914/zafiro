package com.niki914.s3ss10n

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class McpDiscoveryCacheTest {
    @Test
    fun `相同 key 的刷新任务会复用同一个 deferred`() = runTest {
        val cache = McpDiscoveryCache()
        val gate = CompletableDeferred<Unit>()
        var runCount = 0

        val (first, firstCreated) = cache.acquireRefresh("docs", "fp", this) {
            runCount++
            gate.await()
            "ok"
        }
        val (second, secondCreated) = cache.acquireRefresh("docs", "fp", this) {
            runCount++
            "unexpected"
        }

        runCurrent()

        assertTrue(firstCreated)
        assertFalse(secondCreated)
        assertSame(first, second)
        assertEquals(1, runCount)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("ok", first.await())
        assertEquals("ok", second.await())
    }

    @Test
    fun `缓存按 fingerprint 隔离`() = runTest {
        val cache = McpDiscoveryCache()
        val v1Tools = listOf(
            McpDiscoveredTool(
                name = "search_v1",
                description = "first",
                inputSchema = mapOf("type" to "object")
            )
        )
        val v2Tools = listOf(
            McpDiscoveredTool(
                name = "search_v2",
                description = "second",
                inputSchema = mapOf("type" to "object")
            )
        )

        cache.put("docs", "fp1", v1Tools)
        cache.put("docs", "fp2", v2Tools)

        assertEquals(v1Tools, cache.snapshot("docs", "fp1"))
        assertEquals(v2Tools, cache.snapshot("docs", "fp2"))
    }

    @Test
    fun `markDiscovering 保留旧成功 cache`() = runTest {
        val cache = McpDiscoveryCache()
        val tools = listOf(tool("remote_search"))
        cache.commitSuccess("docs", "fp", tools, nowMillis = 100L)

        val snapshot = cache.markDiscovering("docs", "fp", nowMillis = 200L)

        assertEquals(McpDiscoveryState.Discovering, snapshot.state)
        assertEquals(1, snapshot.discoveredToolCount)
        assertEquals(100L, snapshot.lastSuccessAtMillis)
        assertFalse(snapshot.stale)
        assertEquals(tools, cache.snapshot("docs", "fp"))
    }

    @Test
    fun `commitSuccess 写入 available 状态和 lastSuccessAtMillis`() = runTest {
        val cache = McpDiscoveryCache()
        val tools = listOf(tool("remote_search"))

        val snapshot = cache.commitSuccess("docs", "fp", tools, nowMillis = 300L)

        assertEquals("docs", snapshot.serverName)
        assertTrue(snapshot.enabled)
        assertEquals("fp", snapshot.fingerprint)
        assertEquals(McpDiscoveryState.Available, snapshot.state)
        assertEquals(1, snapshot.discoveredToolCount)
        assertEquals(300L, snapshot.lastSuccessAtMillis)
        assertEquals(null, snapshot.errorMessage)
        assertFalse(snapshot.stale)
        assertEquals(tools, cache.snapshot("docs", "fp"))
    }

    @Test
    fun `commitFailure 有旧 cache 时进入 using stale cache`() = runTest {
        val cache = McpDiscoveryCache()
        val tools = listOf(tool("remote_search"))
        cache.commitSuccess("docs", "fp", tools, nowMillis = 100L)

        val (snapshot, policy) = cache.commitFailure(
            serverName = "docs",
            fingerprint = "fp",
            message = "boom",
            nowMillis = 200L
        )

        assertEquals(McpDiscoveryState.UsingStaleCache, snapshot.state)
        assertEquals(McpCachePolicy.UsingStaleCache, policy)
        assertEquals("boom", snapshot.errorMessage)
        assertEquals(100L, snapshot.lastSuccessAtMillis)
        assertEquals(1, snapshot.discoveredToolCount)
        assertTrue(snapshot.stale)
        assertEquals(tools, cache.snapshot("docs", "fp"))
    }

    @Test
    fun `commitFailure 无旧 cache 时进入 failed`() = runTest {
        val cache = McpDiscoveryCache()

        val (snapshot, policy) = cache.commitFailure(
            serverName = "docs",
            fingerprint = "fp",
            message = "boom",
            nowMillis = 200L
        )

        assertEquals(McpDiscoveryState.Failed, snapshot.state)
        assertEquals(McpCachePolicy.NoUsableCache, policy)
        assertEquals("boom", snapshot.errorMessage)
        assertEquals(null, snapshot.lastSuccessAtMillis)
        assertEquals(0, snapshot.discoveredToolCount)
        assertFalse(snapshot.stale)
        assertNull(cache.snapshot("docs", "fp"))
    }

    @Test
    fun `commitIgnoredBecauseConfigChanged 不进入 using stale cache`() = runTest {
        val cache = McpDiscoveryCache()
        val tools = listOf(tool("remote_search"))
        cache.commitSuccess("docs", "fp", tools, nowMillis = 100L)

        val snapshot = cache.commitIgnoredBecauseConfigChanged(
            serverName = "docs",
            fingerprint = "fp",
            message = "config changed",
            nowMillis = 200L
        )

        assertEquals(McpDiscoveryState.Failed, snapshot.state)
        assertEquals("config changed", snapshot.errorMessage)
        assertEquals(100L, snapshot.lastSuccessAtMillis)
        assertEquals(1, snapshot.discoveredToolCount)
        assertFalse(snapshot.stale)
        assertNull(cache.snapshot("docs", "fp"))
    }

    private fun tool(name: String): McpDiscoveredTool {
        return McpDiscoveredTool(
            name = name,
            description = "$name description",
            inputSchema = mapOf("type" to "object")
        )
    }
}
