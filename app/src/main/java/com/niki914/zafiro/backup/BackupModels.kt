package com.niki914.zafiro.backup

import kotlinx.serialization.json.JsonElement

/** 界面上可勾选的六组。一组对应一个或多个分区，选中则整组分区都在包里。 */
enum class BackupGroup(val id: String) {
    Tools("tools"),
    Conversations("conversations"),
    Config("config"),
    ExecutionRules("execution_rules"),
    TakeoverRules("takeover_rules"),
    MaskingRules("masking_rules"),
    ;

    companion object {
        fun fromId(id: String): BackupGroup? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 包内分区。列表用 [Apply.Items]；偏好和单值文档只用 [Apply.Overwrite]。
 * 同时含列表和单值字段的分区，单值字段另标 [singletonApply]。
 */
enum class BackupPartition(
    val id: String,
    val group: BackupGroup,
    val apply: Apply,
    val singletonApply: Apply?,
    val listKey: String?,
    val honorsExplicitEmpty: Boolean,
) {
    Conversations(
        id = "conversations",
        group = BackupGroup.Conversations,
        apply = Apply.Items,
        singletonApply = Apply.Overwrite,
        listKey = "conversations",
        honorsExplicitEmpty = false,
    ),
    Memory(
        id = "memory",
        group = BackupGroup.Conversations,
        apply = Apply.Items,
        singletonApply = null,
        listKey = "memories",
        honorsExplicitEmpty = false,
    ),
    Models(
        id = "models",
        group = BackupGroup.Config,
        apply = Apply.Items,
        singletonApply = Apply.Overwrite,
        listKey = "configs",
        honorsExplicitEmpty = false,
    ),
    Preferences(
        id = "preferences",
        group = BackupGroup.Config,
        apply = Apply.Overwrite,
        singletonApply = null,
        listKey = null,
        honorsExplicitEmpty = false,
    ),
    McpServers(
        id = "mcp_servers",
        group = BackupGroup.Tools,
        apply = Apply.Items,
        singletonApply = null,
        listKey = "servers",
        honorsExplicitEmpty = false,
    ),
    McpService(
        id = "mcp_service",
        group = BackupGroup.Tools,
        apply = Apply.Overwrite,
        singletonApply = null,
        listKey = null,
        honorsExplicitEmpty = false,
    ),
    Skills(
        id = "skills",
        group = BackupGroup.Tools,
        apply = Apply.Items,
        singletonApply = null,
        listKey = null,
        honorsExplicitEmpty = false,
    ),
    BuiltinTools(
        id = "builtin_tools",
        group = BackupGroup.Tools,
        apply = Apply.Overwrite,
        singletonApply = null,
        listKey = null,
        honorsExplicitEmpty = false,
    ),
    PyTools(
        id = "py_tools",
        group = BackupGroup.Tools,
        apply = Apply.Items,
        singletonApply = null,
        listKey = "tools",
        honorsExplicitEmpty = false,
    ),
    TextActions(
        id = "text_actions",
        group = BackupGroup.Tools,
        apply = Apply.Items,
        singletonApply = null,
        listKey = "actions",
        honorsExplicitEmpty = true,
    ),
    ExecutionRules(
        id = "execution_rules",
        group = BackupGroup.ExecutionRules,
        apply = Apply.Items,
        singletonApply = null,
        listKey = "rules",
        honorsExplicitEmpty = true,
    ),
    TakeoverRules(
        id = "takeover_rules",
        group = BackupGroup.TakeoverRules,
        apply = Apply.Items,
        singletonApply = Apply.Overwrite,
        listKey = "rules",
        honorsExplicitEmpty = true,
    ),
    MaskingRules(
        id = "masking_rules",
        group = BackupGroup.MaskingRules,
        apply = Apply.Items,
        singletonApply = null,
        listKey = "rules",
        honorsExplicitEmpty = true,
    ),
    ;

    val jsonName: String get() = "partitions/$id.json"

    companion object {
        fun fromId(id: String): BackupPartition? = entries.firstOrNull { it.id == id }
    }
}

enum class Apply(val wire: String) {
    Items("items"),
    Overwrite("overwrite"),
}

enum class PartitionState(val wire: String) {
    Items("items"),
    Empty("empty"),
    ExplicitEmpty("explicit_empty"),
    Document("document"),
}

enum class SecretsMode(val wire: String) {
    Stripped("stripped"),
    Included("included"),
    ;

    companion object {
        fun fromWire(wire: String): SecretsMode? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 搬家文件和日后的应用前快照共用这一请求。
 * 两者的差别只在 [includeSecrets]。
 */
data class BackupRequest(
    val groups: Set<BackupGroup>,
    val includeSecrets: Boolean = false,
    val createdAtMillis: Long = 0L,
    val appVersion: String = "",
)

data class ConversationBackup(
    val id: String,
    val title: String,
    val titleEdited: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastMessagePreview: String = "",
    val turnCount: Int = 0,
    val leafId: String? = null,
    val configId: String = "",
    val hasUnread: Boolean = false,
    val rootId: String = "",
    val parentId: String? = null,
    val forkTurn: Int? = null,
)

data class EntryBackup(
    val conversationId: String,
    val id: String,
    val parentId: String? = null,
    val timestamp: Long = 0L,
    val messageJson: String,
)

data class SkillSnapshot(
    val id: String,
    val files: Map<String, ByteArray>,
    val bundledSkillMd: ByteArray? = null,
)

/** 导出时的全量读数。未选中的组不会写入包，但读数可以先备齐。 */
data class BackupSnapshot(
    val appStateJson: String = "{}",
    val modelsJson: String = """{"active_id":"","prompt":"","configs":[]}""",
    val memoryJson: String = """{"memories":[]}""",
    val mcpServersJson: String = """{"servers":[]}""",
    val mcpServiceJson: String = """{"enabled":false,"bearerToken":""}""",
    val builtinToolsJson: String = """{"version":2,"enabled":{}}""",
    val pyToolsJson: String = """{"tools":[]}""",
    val textActionsJson: String = """{"actions":[]}""",
    val executionRulesJson: String = """{"rules":[]}""",
    val takeoverRulesJson: String = """{"default_target":"ZAFIRO","rules":[]}""",
    val maskingRulesJson: String = """{"rules":[]}""",
    val skillsStatusJson: String = """{"enabled":{}}""",
    val skills: List<SkillSnapshot> = emptyList(),
    val conversations: List<ConversationBackup> = emptyList(),
    val entries: List<EntryBackup> = emptyList(),
    val readImage: (String) -> ByteArray? = { null },
    val pyInstallApkDir: String = "",
    val pyDownloadFileDir: String = "",
)

data class PartitionSummary(
    val id: String,
    val groupId: String,
    val state: PartitionState,
    val count: Int,
    val apply: Apply,
    val singletonApply: Apply?,
)

data class BackupSummary(
    val formatVersion: Int,
    val appVersion: String,
    val createdAtMillis: Long,
    val secrets: SecretsMode,
    val partitions: Map<String, PartitionSummary>,
    val externalAttachmentPaths: Int,
    val ignoredPartitions: List<String>,
)

sealed interface InspectResult {
    data class Ok(
        val summary: BackupSummary,
        val texts: Map<String, String>,
        val blobNames: List<String>,
    ) : InspectResult

    data class Rejected(val reason: RejectReason) : InspectResult
}

enum class RejectReason {
    NewerFormat,
    UnsafePath,
    NotAnArchive,
    MissingManifest,
}

internal data class ArchiveEntry(
    val name: String,
    val bytes: ByteArray,
)

internal data class RewrittenEntries(
    val messages: List<JsonElement>,
    val blobs: Map<String, ByteArray>,
    val externalAttachmentPaths: Int,
)
