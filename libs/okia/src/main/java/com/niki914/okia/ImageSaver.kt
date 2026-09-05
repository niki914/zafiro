package com.niki914.okia

/**
 * 图片保存器（host 注入）。Okia 纯库不碰文件系统，MCP 返回 base64 图片时
 * 经本接口落地为文件，返回路径引用供 ContentBlock.Image 使用。
 *
 * 入参允许 data URL（`data:image/png;base64,xxx`），实现负责 strip 前缀后 decode。
 * 统一存储路径：filesDir/Zafiro/images/（私有，零权限），SHA-256 内容哈希命名。
 */
fun interface ImageSaver {
    suspend fun save(base64: String, mimeType: String): String?
}
