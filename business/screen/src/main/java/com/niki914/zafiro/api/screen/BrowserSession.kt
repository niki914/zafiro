package com.niki914.zafiro.api.screen

/**
 * 应用内浏览器的会话。这一轮没有实现。
 *
 * `document()` 产出和显示树同一套 [NodeInfo]。标签页属于这一块屏。
 */
interface BrowserSession {
    val url: String

    suspend fun document(): List<NodeInfo>

    suspend fun frame(): ImageFrame

    suspend fun perform(token: SemanticToken, action: NodeAction, text: String?)

    suspend fun hit(action: PointerAction)

    suspend fun history(forward: Boolean)

    suspend fun load(url: String)

    suspend fun tabs(): List<TabInfo>

    suspend fun newTab(url: String?): TabInfo

    suspend fun closeTab(tabId: String)

    suspend fun switchTab(tabId: String)
}

interface BrowserScreen : Screen {
    val session: BrowserSession
    val nodes: NodeTable
}
