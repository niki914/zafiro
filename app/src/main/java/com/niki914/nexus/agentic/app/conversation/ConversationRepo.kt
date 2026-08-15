package com.niki914.nexus.agentic.app.conversation

import android.content.Context
import com.niki914.kai.ChatTurn
import com.niki914.logging.Logger
import java.util.UUID

object ConversationRepo {
    private const val LOG_TAG = "niki914_nexus_ConversationRepo"

    @Volatile
    private var database: ConversationDatabase? = null

    fun init(context: Context) {
        if (database != null) return
        synchronized(this) {
            if (database == null) {
                database = buildConversationDatabase(context.applicationContext)
            }
        }
    }

    suspend fun listConversations(): List<ConversationSummary> {
        val startedAtMs = System.currentTimeMillis()
        return dao().listConversations().map { it.toSummary() }.also { summaries ->
            Logger.i(
                LOG_TAG,
                "list conversations count=${summaries.size} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
        }
    }

    suspend fun getConversation(id: String): ConversationRecord? {
        val startedAtMs = System.currentTimeMillis()
        val conversation = dao().getConversation(id)
        if (conversation == null) {
            Logger.d(
                LOG_TAG,
                "get conversation id=$id notFound " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
            return null
        }
        val history = dao().listTurns(id).mapNotNull { turn ->
            ChatTurnJsonCodec.decode(turn.kind, turn.payloadJson)
        }
        return ConversationRecord(
            summary = conversation.toSummary(),
            draftText = conversation.draftText,
            history = history,
        ).also {
            Logger.i(
                LOG_TAG,
                "get conversation id=$id turnCount=${history.size} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
        }
    }

    suspend fun createConversation(
        firstUserInput: String,
        now: Long = System.currentTimeMillis(),
    ): String {
        val startedAtMs = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        dao().insertConversation(
            ConversationEntity(
                id = id,
                title = ConversationFormatter.titleFromFirstInput(firstUserInput),
                titleEdited = false,
                createdAt = now,
                updatedAt = now,
                lastMessagePreview = ConversationFormatter.previewFromText(firstUserInput),
                turnCount = 0,
                draftText = "",
            ),
        )
        Logger.i(
            LOG_TAG,
            "conversation created id=$id " +
                "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
        return id
    }

    suspend fun forkConversation(
        sourceId: String,
        keepTurnCount: Int,
        now: Long = System.currentTimeMillis(),
    ): String {
        val startedAtMs = System.currentTimeMillis()
        val source = dao().getConversation(sourceId)
            ?: throw IllegalStateException("Source conversation not found: $sourceId")

        val allTurns = dao().listTurns(sourceId)
        val truncated = allTurns.take(keepTurnCount)

        val decodedTurns = truncated.mapNotNull { turn ->
            ChatTurnJsonCodec.decode(turn.kind, turn.payloadJson)
        }
        val preview = ConversationFormatter.previewFromHistory(decodedTurns)

        val newId = UUID.randomUUID().toString()

        dao().insertConversation(
            ConversationEntity(
                id = newId,
                title = source.title,
                titleEdited = true,
                createdAt = now,
                updatedAt = now,
                lastMessagePreview = preview,
                turnCount = truncated.size,
                draftText = "",
            ),
        )

        val newTurns = truncated.mapIndexed { index, turn ->
            turn.copy(id = 0L, conversationId = newId, turnIndex = index)
        }
        dao().insertTurns(newTurns)
        Logger.i(
            LOG_TAG,
            "fork done sourceId=$sourceId keepTurnCount=$keepTurnCount " +
                "newId=$newId turns=${truncated.size} " +
                "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )

        return newId
    }

    suspend fun saveHistory(
        conversationId: String,
        history: List<ChatTurn>,
        now: Long = System.currentTimeMillis(),
    ) {
        if (dao().getConversation(conversationId) == null) return
        val encodedTurns = history.mapIndexedNotNull { index, turn ->
            val encoded = ChatTurnJsonCodec.encode(turn) ?: return@mapIndexedNotNull null
            ConversationTurnEntity(
                conversationId = conversationId,
                turnIndex = index,
                kind = encoded.kind.name,
                payloadJson = encoded.payloadJson,
                createdAt = now,
            )
        }
        dao().replaceTurnsAndMetadata(
            conversationId = conversationId,
            turns = encodedTurns,
            updatedAt = now,
            lastMessagePreview = ConversationFormatter.previewFromHistory(history),
            turnCount = encodedTurns.size,
        )
    }

    suspend fun updateDraft(conversationId: String, draftText: String) {
        dao().updateDraft(conversationId = conversationId, draftText = draftText)
    }

    suspend fun deleteConversation(id: String) {
        dao().deleteConversation(id)
    }

    suspend fun renameConversation(id: String, title: String) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) return
        dao().renameConversation(id = id, title = trimmedTitle)
    }

    internal suspend fun closeForTest() {
        synchronized(this) {
            database?.close()
            database = null
        }
    }

    private fun dao(): ConversationDao {
        return requireNotNull(database) {
            "ConversationRepo.init(context) must be called before use."
        }.conversationDao()
    }

    private fun ConversationEntity.toSummary(): ConversationSummary {
        return ConversationSummary(
            id = id,
            title = title,
            titleEdited = titleEdited,
            createdAt = createdAt,
            updatedAt = updatedAt,
            lastMessagePreview = lastMessagePreview,
            turnCount = turnCount,
        )
    }
}
