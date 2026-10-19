package com.niki914.zafiro.app.conversation

import com.niki914.zafiro.api.model.ConversationTurn

data class TurnBranchState(
    val current: Int,
    val total: Int,
    val variantConversationIds: List<String>,
)

object ConversationBranchTree {

    fun computeTurnBranches(
        currentConversationId: String,
        turns: List<ConversationTurn>,
        family: List<ConversationSummary>,
    ): Map<Long, TurnBranchState> {
        if (family.size <= 1 || turns.isEmpty()) return emptyMap()

        val summaryById = family.associateBy { it.id }
        if (!summaryById.containsKey(currentConversationId)) return emptyMap()

        val result = mutableMapOf<Long, TurnBranchState>()

        turns.forEachIndexed { turnIndex, _ ->
            val branchState = computeForTurn(
                currentConversationId = currentConversationId,
                turnIndex = turnIndex,
                family = family,
                summaryById = summaryById,
            )
            if (branchState != null && branchState.total > 1) {
                result[turnIndex.toLong()] = branchState
            }
        }

        return result
    }

    private fun computeForTurn(
        currentConversationId: String,
        turnIndex: Int,
        family: List<ConversationSummary>,
        summaryById: Map<String, ConversationSummary>,
    ): TurnBranchState? {
        val currentHead = findVariantHead(currentConversationId, turnIndex, summaryById) ?: return null

        val candidates = if (turnIndex == 0) {
            family
        } else {
            val currentPrefixHead = findVariantHead(currentConversationId, turnIndex - 1, summaryById)
            family.filter {
                findVariantHead(it.id, turnIndex - 1, summaryById) == currentPrefixHead
            }
        }

        if (candidates.isEmpty()) return null

        val groups = candidates.groupBy { conv ->
            findVariantHead(conv.id, turnIndex, summaryById)
        }

        val orderedHeads = groups.keys.filterNotNull().sortedBy { headId ->
            summaryById[headId]?.createdAt ?: 0L
        }

        if (orderedHeads.size <= 1) return null

        val currentIdx = orderedHeads.indexOf(currentHead)
        if (currentIdx < 0) return null

        val variantTargetIds = orderedHeads.map { headId ->
            val groupConvs = groups[headId].orEmpty()
            groupConvs.maxByOrNull { it.updatedAt }?.id ?: headId
        }

        return TurnBranchState(
            current = currentIdx + 1,
            total = orderedHeads.size,
            variantConversationIds = variantTargetIds,
        )
    }

    private fun findVariantHead(
        conversationId: String,
        turnIndex: Int,
        summaryById: Map<String, ConversationSummary>,
    ): String? {
        var cursor = summaryById[conversationId] ?: return null
        val visited = HashSet<String>()
        while (visited.add(cursor.id)) {
            val forkTurn = cursor.forkTurn
            val parentId = cursor.parentId
            if (forkTurn == null || parentId == null) {
                return cursor.id
            }
            if (forkTurn == turnIndex) {
                return cursor.id
            }
            if (forkTurn > turnIndex) {
                cursor = summaryById[parentId] ?: return cursor.id
            } else {
                return cursor.id
            }
        }
        return cursor.id
    }
}
