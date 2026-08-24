package com.niki914.zafiro.chat

import com.niki914.logging.Logger
import com.niki914.okia.Okia
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * MCP 后台刷新调度（问题 4 修复）：签名状态机 + 失败退避重试。
 *
 * 签名三态分离：
 * - desired = 当前配置想要（schedule 每次更新）
 * - success = 全部目标服务器成功刷新过的签名。异常（撞活跃回合 / 实例已
 *   关闭 / 网络）与部分失败（McpRefreshResult.failedServers 非空）都不更新
 *   → 下次 schedule 重新触发，失败不再被永久跳过
 * - inFlight 防重：同一时刻至多一个后台刷新；in-flight 期间配置变化不吞——
 *   刷新完成后协程检查 desired，继续刷最新签名
 *
 * 失败语义：有上限退避（撞回合短退避，网络/部分失败指数退避），达上限放弃
 * 本轮，等下一次 schedule（每次 send 前的 refresh）重新触发。
 * 重试为全量（能力边界在 OKIA 库 API：refreshMcpTools 无按服务器刷入口；
 * 服务器级跳过留库侧，见 ISSUES_okia-integration.md）。
 */
internal class McpRefreshScheduler(private val scope: CoroutineScope) {

    private val logTag = "niki914_nexus_McpRefreshScheduler"

    @Volatile
    private var desiredSignature: String? = null

    @Volatile
    private var successSignature: String? = null

    private val inFlight = AtomicBoolean(false)

    /** 签名变化才起后台刷新（不 await，不阻塞回合）。见类注释。 */
    fun schedule(session: Okia, signature: String) {
        desiredSignature = signature
        if (signature == successSignature) return
        if (inFlight.get()) return
        launchRefresh(session, signature)
    }

    fun reset() {
        desiredSignature = null
        successSignature = null
        inFlight.set(false)
    }

    private fun launchRefresh(session: Okia, signature: String) {
        if (!inFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                var attempt = 0
                var currentSession = session
                var currentSignature = signature
                while (true) {
                    val startedAtMs = System.currentTimeMillis()
                    val result = try {
                        currentSession.refreshMcpTools()
                    } catch (throwable: Throwable) {
                        if (throwable is CancellationException) throw throwable
                        attempt++
                        Logger.e(
                            logTag,
                            "mcp refresh failed attempt=$attempt " +
                                "elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                                "errorType=${throwable::class.simpleName} message=${throwable.message}"
                        )
                        val backoffMs = backoffMs(attempt, throwable)
                        if (backoffMs == null) break // 达上限 / 不可恢复：等下次 schedule 重试
                        delay(backoffMs)
                        null
                    }
                    if (result != null) {
                        if (result.failedServers.isEmpty()) {
                            successSignature = currentSignature
                            Logger.i(
                                logTag,
                                "mcp refresh done elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                                    "refreshed=${result.refreshedServers.joinToString(",") { "\"$it\"" }}"
                            )
                            attempt = 0
                        } else {
                            // 部分服务器失败：不算成功（否则失败服务器被永久跳过，
                            // 问题 4 原状），退避后全量重试
                            attempt++
                            Logger.e(
                                logTag,
                                "mcp refresh partial failed attempt=$attempt " +
                                    "elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                                    "failed=${result.failedServers.joinToString(",") { "\"$it\"" }}"
                            )
                            val backoffMs = backoffMs(attempt, null)
                            if (backoffMs == null) break
                            delay(backoffMs)
                        }
                    }
                    // 配置在 in-flight 期间变化 → 继续刷最新 desired（不吞）；
                    // 成功覆盖最新配置 → 结束。
                    val latestDesired = desiredSignature
                    if (latestDesired != null && latestDesired == successSignature) break
                    if (latestDesired != null) currentSignature = latestDesired
                }
            } finally {
                inFlight.set(false)
            }
        }
    }

    private fun backoffMs(attempt: Int, throwable: Throwable?): Long? {
        val message = throwable?.message.orEmpty()
        return when {
            message.contains("closed") -> null
            message.contains("active turn") -> when (attempt) {
                1 -> 500L
                2 -> 500L
                3 -> 1_000L
                else -> null
            }
            else -> when (attempt) {
                1 -> 1_000L
                2 -> 3_000L
                else -> null
            }
        }
    }
}
