package com.niki914.zafiro.app.conversation

import com.niki914.logging.Logger

/**
 * 历史会话搜索仓储：按标题与消息正文综合检索。
 *
 * 从 [ConversationRepo] 按领域职责拆出，共享同一个 Room 数据库实例。
 */
object ConversationSearchRepo {
    private const val LOG_TAG = "niki914_zafiro_ConversationSearchRepo"

    /**
     * 搜索历史会话：按标题与消息正文综合检索。
     *
     * 匹配优先级与策略：
     * 1. 优先在当前展示的所有最新分支会话中检索（与 [ConversationRepo.observeConversations] 范围一致）；
     * 2. 若条目正文命中（排除 JSON 语法结构假阳性），提取居中窗口片段 [ConversationSearchResult.matchedSnippet] 并替换预览；
     * 3. 若会话无落盘条目命中但其 [ConversationSummary.lastMessagePreview] 命中，降级提取该预览的片段；
     * 4. 标题与正文均未命中则过滤，结果保持会话 [ConversationSummary.updatedAt] 降序。
     */
    suspend fun searchConversations(query: String): List<ConversationSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val startedAtMs = System.currentTimeMillis()
        val dao = ConversationRepo.dao()
        val branches = dao.listLatestBranches().map { with(ConversationRepo) { it.toSummary() } }
        val matchingEntries = dao.searchEntries(trimmed)
        val entriesByConversationId = matchingEntries.groupBy { it.conversationId }

        val results = branches.mapNotNull { summary ->
            val titleMatches = summary.title.contains(trimmed, ignoreCase = true)
            var matchedSnippet: String? = null
            var bodyMatches = false

            val candidateEntries = entriesByConversationId[summary.id].orEmpty()
            for (entryEntity in candidateEntries) {
                val entry = with(ConversationRepo) { entryEntity.toConversationEntry() } ?: continue
                val cleanText = ConversationFormatter.previewTextOf(entry.message)
                if (cleanText.contains(trimmed, ignoreCase = true)) {
                    matchedSnippet = SearchSnippetHelper.extractSnippet(cleanText, trimmed)
                    bodyMatches = true
                    break
                }
            }

            if (!bodyMatches && summary.lastMessagePreview.contains(trimmed, ignoreCase = true)) {
                matchedSnippet = SearchSnippetHelper.extractSnippet(summary.lastMessagePreview, trimmed)
                bodyMatches = true
            }

            if (titleMatches || bodyMatches) {
                ConversationSearchResult(
                    summary = summary,
                    matchedSnippet = matchedSnippet,
                    matchedInTitle = titleMatches,
                    matchedInBody = bodyMatches,
                )
            } else {
                null
            }
        }

        Logger.d(
            LOG_TAG,
            "searchConversations query=\"$trimmed\" totalBranches=${branches.size} " +
                    "matchingEntries=${matchingEntries.size} results=${results.size} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
        return results
    }
}
