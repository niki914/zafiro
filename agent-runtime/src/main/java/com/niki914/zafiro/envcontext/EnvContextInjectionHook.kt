package com.niki914.zafiro.envcontext

import com.niki914.okia.conversation.Conversation
import com.niki914.okia.hooks.Hooks
import com.niki914.okia.hooks.SerializationHolder
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.zafiro.api.screen.VirtualDisplayContext
import com.niki914.zafiro.api.text.SummaryBlock
import com.niki914.zafiro.settings.model.RuntimeLlmConfig
import java.time.ZoneId
import java.util.Locale

class EnvContextInjectionHook(
    private val getLlmConfig: suspend () -> RuntimeLlmConfig,
    private val deviceStateProvider: DeviceStateProvider = DeviceStateProvider { DeviceStateSnapshot() },
    private val getConversation: () -> Conversation? = { null },
    private val virtualDisplayProvider: () -> VirtualDisplayContext? = { null },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zoneId: () -> ZoneId = { ZoneId.systemDefault() },
    private val locale: () -> Locale = { Locale.getDefault() },
) : Hooks {

    override suspend fun beforeSerialization(request: SerializationHolder) {
        val config = getLlmConfig().envContext
        if (!config.enabled || config.keys.isEmpty()) return

        val history = request.history
        if (history.isEmpty()) return

        val userMessageIndices = history.mapIndexedNotNull { index, msg ->
            if (isUserPrompt(msg)) index else null
        }
        if (userMessageIndices.isEmpty()) return

        val activeUserIndex = userMessageIndices.last()
        val activeUserMessage = history[activeUserIndex]
        val committedUserEntries = getConversation()?.history?.filter { isUserPrompt(it.message) }.orEmpty()

        var historyChanged = false
        val newHistory = history.mapIndexed { index, message ->
            if (message !is Message.User || isSummaryMessage(message)) return@mapIndexed message

            val isLatest = (index == activeUserIndex)
            val timestamp: Long
            val deviceState: DeviceStateSnapshot?
            val virtualDisplayText: String?

            if (isLatest) {
                timestamp = clock()
                deviceState = deviceStateProvider.getDeviceState()
                virtualDisplayText = EnvContextFormatter.formatVirtualDisplay(virtualDisplayProvider())
            } else {
                val userTurnOrder = userMessageIndices.indexOf(index)
                val offsetFromActive = userMessageIndices.size - 1 - userTurnOrder
                val committedIndex = if (committedUserEntries.isNotEmpty() &&
                    committedUserEntries.last().message == activeUserMessage
                ) {
                    committedUserEntries.lastIndex - offsetFromActive
                } else {
                    committedUserEntries.size - offsetFromActive
                }
                val entryTimestamp = committedUserEntries.getOrNull(committedIndex)?.timestamp
                timestamp = if (entryTimestamp != null && entryTimestamp > 0L) {
                    entryTimestamp
                } else {
                    clock()
                }
                val fullState = deviceStateProvider.getDeviceState()
                deviceState = if (fullState.model.isNotBlank()) DeviceStateSnapshot(model = fullState.model) else null
                virtualDisplayText = null
            }

            var contentChanged = false
            val lastTextBlockIndex = message.content.indexOfLast { it is ContentBlock.Text }
            val newContent = if (lastTextBlockIndex == -1) {
                val block = EnvContextFormatter.formatContextBlock(
                    timestamp = timestamp,
                    deviceState = deviceState,
                    config = config,
                    zoneId = zoneId(),
                    locale = locale(),
                    virtualDisplayText = virtualDisplayText,
                )
                if (!block.isNullOrBlank()) {
                    contentChanged = true
                    message.content + ContentBlock.Text(block)
                } else {
                    message.content
                }
            } else {
                message.content.mapIndexed { blockIndex, block ->
                    if (block is ContentBlock.Text) {
                        val updatedText = if (blockIndex == lastTextBlockIndex) {
                            EnvContextFormatter.injectIntoText(
                                text = block.text,
                                timestamp = timestamp,
                                deviceState = deviceState,
                                config = config,
                                zoneId = zoneId(),
                                locale = locale(),
                                virtualDisplayText = virtualDisplayText,
                            )
                        } else {
                            EnvContextFormatter.replacePlaceholders(
                                text = block.text,
                                timestamp = timestamp,
                                deviceState = deviceState,
                                config = config,
                                zoneId = zoneId(),
                                locale = locale(),
                                virtualDisplayText = virtualDisplayText,
                            )
                        }
                        if (updatedText != block.text) {
                            contentChanged = true
                            block.copy(text = updatedText)
                        } else {
                            block
                        }
                    } else {
                        block
                    }
                }
            }

            if (contentChanged) {
                historyChanged = true
                message.copy(content = newContent)
            } else {
                message
            }
        }

        if (historyChanged) {
            request.write(request.snapshot, newHistory, "env_context")
        }
    }

    private fun isUserPrompt(msg: Message): Boolean {
        return msg is Message.User && !isSummaryMessage(msg)
    }

    private fun isSummaryMessage(msg: Message.User): Boolean {
        val text = msg.content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
        return SummaryBlock.strip(text).payload != null
    }
}
