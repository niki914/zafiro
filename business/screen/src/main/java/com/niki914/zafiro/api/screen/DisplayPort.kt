package com.niki914.zafiro.api.screen

interface NodeTable {
    val version: String

    fun remember(nodes: List<NodeInfo>): String

    fun resolve(token: SemanticToken): NodeInfo?
}

interface FrameBuffer {
    suspend fun latest(): ImageFrame?
}

interface DisplayPort {
    val displayId: Int

    suspend fun readNodes(): List<NodeInfo>

    suspend fun frame(): ImageFrame

    suspend fun perform(token: SemanticToken, action: NodeAction, text: String?): ActionStatus

    suspend fun inject(action: PointerAction): ActionStatus

    suspend fun show(target: ShowTarget): ShowStatus
}

interface DisplayScreen : Screen {
    val port: DisplayPort
    val nodes: NodeTable
}

interface DefaultDisplayPort : DisplayPort

interface VirtualDisplayPort : DisplayPort {
    val frames: FrameBuffer
}

interface VirtualDisplayHost {
    suspend fun create(): DisplayPort

    suspend fun destroy(displayId: Int)
}

interface ScreenDirectory : Screens {
    val phone: DisplayScreen
    val displays: VirtualDisplayHost
}
