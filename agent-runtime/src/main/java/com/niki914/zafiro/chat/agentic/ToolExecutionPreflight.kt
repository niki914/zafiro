package com.niki914.zafiro.chat.agentic

import com.niki914.xposed.api.util.ContextProvider
import com.niki914.xposed.api.util.LockState
import com.niki914.zafiro.api.AgentControl
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionState
import com.niki914.zafiro.service.requireService
import com.niki914.zafiro.settings.RuntimeEnvironment
import com.niki914.zafiro.util.TextPatternMatcher
import com.niki914.zafiro.settings.model.RuntimeExecutionRule as ExecutionRule
import com.niki914.zafiro.settings.model.RuntimeExecutionRuleEnabledMode as ExecutionRuleEnabledMode
import java.io.File
import kotlinx.coroutines.CancellationException

data class PreflightDecision(
    val allowed: Boolean,
    val code: String = "OK",
    val reason: String = "",
    val matchedRuleId: String? = null,
    val matchedRuleName: String? = null,
    val matchedPattern: String? = null,
)

/**
 * 工具执行前检查（preflight）：一段代码 / 命令在真正跑之前做两件事——
 *
 * 1. [evaluate]：用户配置的执行规则（正则）命中即拦下。保存期（UI 校验、
 *    py_meta_tools write）与执行期都调用。
 * 2. [ensurePathAccess]：静态扫描文本里的绝对路径，命中沙箱外的公共区域
 *    （/sdcard、/storage 等）就阻塞申请一次存储权限。只在执行期调用；
 *    结果不影响执行——被拒绝、拿不到都照常放行，失败的读写由执行期自然暴露。
 *    用户不需要 review agent 的每行代码，一次权限弹窗的代价远小于漏报。
 *
 * 两者都是启发式（正则水位）：动态拼接出来的路径扫不到，交给运行时报错。
 */
class ToolExecutionPreflight(
    private val listExecutionRules: suspend () -> List<ExecutionRule> = {
        RuntimeEnvironment.awaitSettingsGateway().listExecutionRules()
    },
    private val isUnlocked: suspend () -> Boolean = { LockState.isUnlocked() },
    private val agentControlProvider: () -> AgentControl? = {
        runCatching { requireService<AgentControl>() }.getOrNull()
    },
    private val permissionsProvider: () -> PermissionManager? = {
        runCatching { requireService<PermissionManager>() }.getOrNull()
    },
    private val sandboxRootsProvider: () -> Set<String> = ::defaultSandboxRoots,
) {
    suspend fun evaluate(command: String, toolName: String): PreflightDecision {
        val rules = listExecutionRules()
        if (rules.isEmpty()) {
            return PreflightDecision(allowed = true)
        }
        val unlocked = if (rules.any { it.enabledMode == ExecutionRuleEnabledMode.LOCKED_ONLY }) {
            isUnlocked()
        } else {
            true
        }
        val candidates = command.matchCandidates()
        for (rule in rules.filter { it.isActive(unlocked) }) {
            val pattern = rule.patterns.asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .firstOrNull { pattern ->
                    candidates.any { candidate ->
                        TextPatternMatcher.matches(
                            candidate,
                            pattern
                        )
                    }
                }
                ?: continue
            val blocked = PreflightDecision(
                allowed = false,
                code = "RULE_BLOCKED",
                reason = "Command blocked by execution rule '${rule.name}' with pattern '$pattern'.",
                matchedRuleId = rule.id,
                matchedRuleName = rule.name,
                matchedPattern = pattern,
            )
            when (rule.enabledMode) {
                ExecutionRuleEnabledMode.ALWAYS, ExecutionRuleEnabledMode.LOCKED_ONLY -> return blocked
                ExecutionRuleEnabledMode.DISABLED -> {}
                ExecutionRuleEnabledMode.CONFIRM -> {
                    val agentControl = agentControlProvider()
                    val decision = agentControl?.decideApproval(
                        ApprovalRequest.ToolExecution(
                            toolName = toolName,
                            command = command,
                            ruleName = rule.name,
                        )
                    ) ?: ApprovalDecision.Abstain
                    when (decision) {
                        ApprovalDecision.Allow -> continue
                        ApprovalDecision.Deny -> return blocked.copy(
                            code = "CONFIRM_DENIED",
                            reason = "The user denied this operation.",
                        )
                        ApprovalDecision.Abstain -> return blocked.copy(
                            code = "CONFIRM_UNAVAILABLE",
                            reason = "Tool execution requires user confirmation, but this session " +
                                    "cannot request permission from the user. The operation was denied.",
                        )
                    }
                }
            }
        }
        return PreflightDecision(allowed = true)
    }

    /**
     * 文本里有指向沙箱外公共区域的绝对路径时，阻塞申请一次存储权限。
     * 已授权直接返回；申请抛异常吞掉照常返回——它只负责「先试一把」，不负责拦。
     */
    suspend fun ensurePathAccess(text: String) {
        if (extractExternalPathIntents(text, sandboxRootsProvider()).isEmpty()) return
        val permissions = permissionsProvider() ?: return
        if (permissions.status(Permission.STORAGE) == PermissionState.GRANTED) return
        try {
            permissions.request(Permission.STORAGE)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    /**
     * 提取文本里「值得为它要一次存储权限」的绝对路径：命中公共前缀、
     * 且不在沙箱内。纯函数，单测覆盖这一层。
     */
    internal fun extractExternalPathIntents(text: String, sandboxRoots: Set<String>): Set<String> =
        ABSOLUTE_PATH.findAll(text)
            .map { it.value.trimEnd('.', '/') }
            .filter { path -> WATCHED_PREFIXES.any { prefix -> isSegmentPrefix(path, prefix) } }
            .filter { path -> sandboxRoots.none { root -> isSegmentPrefix(path, root) } }
            .toSet()

    /** `/data/data/pkg.x` 不得命中根 `/data/data/pkg`：前缀必须落在路径段边界上。 */
    private fun isSegmentPrefix(path: String, root: String): Boolean =
        path == root || path.startsWith("$root/")

    private fun String.shellLikeTokens(): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false

        fun flush() {
            if (current.isNotEmpty()) {
                tokens += current.toString()
                current.clear()
            }
        }

        for (char in this) {
            when {
                escaped -> {
                    current.append(char)
                    escaped = false
                }

                char == '\\' -> escaped = true
                quote != null -> {
                    if (char == quote) {
                        quote = null
                    } else {
                        current.append(char)
                    }
                }

                char == '\'' || char == '"' -> quote = char
                char.isWhitespace() || char in SHELL_TOKEN_SEPARATORS -> flush()
                else -> current.append(char)
            }
        }
        if (escaped) {
            current.append('\\')
        }
        flush()
        return tokens
    }

    private fun String.matchCandidates(): List<String> {
        val candidates = linkedSetOf<String>()
        collectMatchCandidates(depth = 0, candidates = candidates)
        return candidates.toList()
    }

    private fun String.collectMatchCandidates(depth: Int, candidates: MutableSet<String>) {
        if (depth > MAX_SHELL_PAYLOAD_DEPTH) {
            return
        }
        val tokens = shellLikeTokens()
        val normalizedTokens = tokens
            .map { it.normalizedShellToken() }
            .filter { it.isNotBlank() }
        candidates += this
        candidates += normalizedTokens.joinToString(separator = " ")
        normalizedTokens.nestedShellPayloads().forEach { payload ->
            candidates += payload
            payload.collectMatchCandidates(depth = depth + 1, candidates = candidates)
        }
    }

    private fun String.normalizedShellToken(): String {
        return lowercase()
            .trim()
            .trim('"', '\'')
    }

    private fun List<String>.nestedShellPayloads(): List<String> {
        val payloads = mutableListOf<String>()
        for (index in indices) {
            val executable = this[index].executableName()
            val payload = when {
                executable in SHELL_COMMANDS -> shellCommandPayloadAfterC(startIndex = index + 1)
                executable == "eval" -> drop(index + 1).joinToString(" ").takeIf { it.isNotBlank() }
                else -> null
            }
            if (payload != null) {
                payloads += payload
            }
        }
        return payloads
    }

    private fun List<String>.shellCommandPayloadAfterC(startIndex: Int): String? {
        for (index in startIndex until size) {
            val token = this[index]
            if (token == "-c") {
                return getOrNull(index + 1)
            }
            if (!token.startsWith("-")) {
                return null
            }
        }
        return null
    }

    private fun String.executableName(): String {
        return substringAfterLast('/')
    }

    private fun ExecutionRule.isActive(unlocked: Boolean): Boolean {
        return when (enabledMode) {
            ExecutionRuleEnabledMode.ALWAYS, ExecutionRuleEnabledMode.CONFIRM -> true
            ExecutionRuleEnabledMode.LOCKED_ONLY -> !unlocked
            ExecutionRuleEnabledMode.DISABLED -> false
        }
    }

    companion object {
        private const val MAX_SHELL_PAYLOAD_DEPTH = 8
        private val SHELL_TOKEN_SEPARATORS = setOf(';', '&', '|', '`', '$', '(', ')', '<', '>')
        private val SHELL_COMMANDS = setOf("sh", "bash", "mksh")

        /**
         * 沙箱外、值得为它试一次存储权限的路径根。/data 本身权限救不了
         * （all-files 只管 /sdcard、/storage），但误报代价只是一次静默查询，
         * 宁滥勿缺。
         */
        internal val WATCHED_PREFIXES = listOf("/sdcard", "/storage", "/mnt", "/data")

        /**
         * 文本里的绝对路径。lookbehind 排除 URL（`https://a.com/b`）、
         * 相对分段（`src/main`）与转义（`\/`）；首段至少一个字符，
         * 单独的 `/` 不算路径。段内允许 `.`（文件名）与 `-`。
         */
        private val ABSOLUTE_PATH = Regex("""(?<![\w.:/])/[A-Za-z0-9_.\-]+(?:/[A-Za-z0-9_.\-]+)*/?""")

        /**
         * app 沙箱的典型指向：真实私有目录 + `/data/data/<pkg>` 与
         * `/data/user/0/<pkg>` 两种别名（同一位置的多种写法都排除）。
         * 无 Context（单测）返回空集合。
         */
        private fun defaultSandboxRoots(): Set<String> {
            val context = ContextProvider.awaitIfAvailable() ?: return emptySet()
            return setOf(
                context.filesDir,
                context.cacheDir,
                context.noBackupFilesDir,
                File("/data/data/${context.packageName}"),
                File("/data/user/0/${context.packageName}"),
            ).map { it.absolutePath }.toSet()
        }
    }
}
