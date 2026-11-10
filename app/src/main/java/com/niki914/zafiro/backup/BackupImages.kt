package com.niki914.zafiro.backup

import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.zafiro.api.text.FilesBlock
import com.niki914.zafiro.api.util.tryOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

internal fun rewriteEntries(
    entries: List<EntryBackup>,
    readImage: (String) -> ByteArray?,
): RewrittenEntries {
    val decoded = entries.map { entry ->
        tryOrNull { backupJson.decodeFromString(Message.serializer(), entry.messageJson) }
    }
    val pathToRef = linkedMapOf<String, String>()
    val blobs = linkedMapOf<String, ByteArray>()
    decoded.forEach { message ->
        if (message == null) return@forEach
        imagePaths(message).forEach { path ->
            rememberImage(path, readImage, pathToRef, blobs)
        }
    }
    val messages = entries.mapIndexed { index, entry ->
        val message = decoded[index]
        val element = if (message == null) {
            tryOrNull { backupJson.parseToJsonElement(entry.messageJson) }
                ?: JsonPrimitive(entry.messageJson)
        } else {
            backupJson.encodeToJsonElement(
                Message.serializer(),
                rewriteMessage(message, pathToRef),
            )
        }
        applyPathMap(element, pathToRef)
    }
    val external = decoded.sumOf { message ->
        if (message == null) 0 else externalAttachmentCount(message)
    }
    return RewrittenEntries(messages, blobs, external)
}

internal fun externalAttachmentCount(message: Message): Int {
    if (message !is Message.User) return 0
    return message.content.filterIsInstance<ContentBlock.Text>().sumOf { block ->
        FilesBlock.strip(block.text).files.size
    }
}

internal fun imagePaths(message: Message): List<String> {
    return when (message) {
        is Message.User -> message.content.mapNotNull { imagePath(it) }
        is Message.Assistant -> message.message.content.mapNotNull { imagePath(it) }
        is Message.ToolResult -> {
            val outcome = message.outcome
            if (outcome is ToolCallOutcome.Success) {
                outcome.images.map { it.path }
            } else {
                emptyList()
            }
        }
    }
}

private fun imagePath(block: ContentBlock): String? {
    return (block as? ContentBlock.Image)?.path
}

private fun rememberImage(
    path: String,
    readImage: (String) -> ByteArray?,
    pathToRef: MutableMap<String, String>,
    blobs: MutableMap<String, ByteArray>,
) {
    if (path in pathToRef || !path.startsWith("/")) return
    val bytes = readImage(path)
    if (bytes == null) {
        pathToRef[path] = unpackedRef(path)
        return
    }
    val ref = "blobs/${sha256Hex(bytes)}.jpg"
    pathToRef[path] = ref
    blobs.putIfAbsent(ref, bytes)
}

private fun unpackedRef(path: String): String {
    val name = path.substringAfterLast('/').takeIf { safeFileName(it) } ?: "unknown"
    return "blobs/unpacked/$name"
}

private fun safeFileName(name: String): Boolean {
    if (name.isEmpty() || name.length > 80) return false
    return name.all { char -> char.isLetterOrDigit() || char == '.' || char == '_' || char == '-' }
}

private fun rewriteMessage(message: Message, pathToRef: Map<String, String>): Message {
    return when (message) {
        is Message.User -> message.copy(content = message.content.map { rewriteBlock(it, pathToRef) })
        is Message.Assistant -> message.copy(
            message = message.message.copy(
                content = message.message.content.map { rewriteBlock(it, pathToRef) },
            ),
        )
        is Message.ToolResult -> {
            val outcome = message.outcome
            if (outcome is ToolCallOutcome.Success) {
                message.copy(
                    outcome = outcome.copy(
                        images = outcome.images.map { image ->
                            image.copy(path = pathToRef[image.path] ?: image.path)
                        },
                    ),
                )
            } else {
                message
            }
        }
    }
}

private fun rewriteBlock(block: ContentBlock, pathToRef: Map<String, String>): ContentBlock {
    val image = block as? ContentBlock.Image ?: return block
    return image.copy(path = pathToRef[image.path] ?: image.path)
}

private fun applyPathMap(element: JsonElement, pathToRef: Map<String, String>): JsonElement {
    if (pathToRef.isEmpty()) return element
    var text = element.toString()
    pathToRef.entries.sortedByDescending { it.key.length }.forEach { (from, to) ->
        text = text.replace(from, to)
    }
    return tryOrNull { backupJson.parseToJsonElement(text) } ?: element
}

internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}
