package com.niki914.okia.mcp

import com.niki914.okia.transport.HttpTimeouts
import com.niki914.okia.transport.OkHttpEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket

/**
 * 真实 MCP 服务器集成测试：官方 @modelcontextprotocol/server-everything
 * （本地 Node，HTTP streamable，有状态 session 模式）。服务器未启动时
 * 自动跳过（Assume），不是失败。验证：
 * - 握手 + 会话头往返（该服务器强制要求 mcp-session-id，不带即 -32000）
 * - tools/list 真实工具发现（echo 等）
 * - tools/call 真实调用（echo 往返）
 * - AutoDetect 对该服务器探测后的 legacy 回退（server/discover →
 *   -32000 not-initialized → legacy）
 * Design source: 用户在 2026-08-17 提供的测试服务器（npm
 * @modelcontextprotocol/server-everything，本机 3001 端口）。
 */
class McpServerEverythingIntegrationTest {

    private val baseUrl = "http://localhost:3001/mcp"

    @Before
    fun requireServer() {
        assumeTrue(
            "server-everything 未启动（npm 安装后运行 " +
                    "node node_modules/@modelcontextprotocol/server-everything/dist/index.js streamableHttp）",
            isServerUp()
        )
    }

    private fun isServerUp(): Boolean = try {
        Socket().use { it.connect(java.net.InetSocketAddress("localhost", 3001), 1000) }
        true
    } catch (e: Exception) {
        false
    }

    private fun server(): McpServer = McpServer(
        name = "everything",
        transport = McpTransport.Http(baseUrl),
        headers = emptyMap(),
        enabled = true
    )

    private fun client() =
        LegacyStreamableHttpMcpClient(OkHttpEngine(), HttpTimeouts(5000, 5000, 5000))

    @Test
    fun `discovers real tools including echo`() = runBlocking {
        val tools = client().discoverTools(server())
        assertTrue("tools 非空", tools.isNotEmpty())
        val echo = tools.firstOrNull { it.name == "echo" }
        assertNotNull("发现 echo 工具", echo)
        assertNotNull("echo 有描述", echo!!.description)
        assertNotNull("echo 有 inputSchema", echo.inputSchemaJson)
    }

    @Test
    fun `calls echo tool and gets the input back`() = runBlocking {
        val c = client()
        // 同一实例：discover 拿会话 → call 复用会话（该服务器强制 session 模式）
        c.discoverTools(server())
        val result = c.callTool(server(), "echo", """{"message":"okia-integration"}""")
        assertFalse("echo 成功", result.isError)
        val text = result.content.map { (it as McpContentBlock.Text).text }.firstOrNull()!!
        assertTrue("echo 回显输入: $text", text.contains("okia-integration"))
    }

    @Test
    fun `auto detect falls back to legacy on the everything server`() = runBlocking {
        val auto = AutoDetectMcpClient(
            legacy = LegacyStreamableHttpMcpClient(OkHttpEngine(), HttpTimeouts(5000, 5000, 5000)),
            discovery = DiscoveryStreamableHttpMcpClient(
                OkHttpEngine(),
                HttpTimeouts(5000, 5000, 5000)
            )
        )
        val tools = auto.discoverTools(server())
        assertTrue("AutoDetect 发现工具", tools.any { it.name == "echo" })
        val result = auto.callTool(server(), "echo", """{"message":"via-auto"}""")
        assertFalse(result.isError)
        assertTrue(
            (result.content[0] as McpContentBlock.Text).text.contains("via-auto")
        )
    }
}