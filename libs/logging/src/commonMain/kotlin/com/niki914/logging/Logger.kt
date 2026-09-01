package com.niki914.logging

import com.niki914.logging.Logger.enableScope
import com.niki914.logging.Logger.install
import com.niki914.logging.Logger.level
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 分级日志门面。
 *
 * - 级别门控：低于 [level] 的日志不输出。
 * - scope 门控：空集合 = 全部放行；[enableScope] 添加精确 TAG 或尾部带 `*` 的前缀规则。
 * - 后端：默认平台后端，[install] 整体替换（replace 语义）。
 * - `d(tag, msg, logInRelease)`：默认参数 false 表示普通调试日志，debug 构建才输出；
 *   `logInRelease = true` 表示该条日志在 release 构建也输出。
 */
object Logger {

    /** 全局级别阈值，低于该级别的日志被跳过。 */
    var level: Level = Level.DEBUG

    /**
     * debug 构建判定源。由进程入口注册（Android 上基于 ApplicationInfo.FLAG_DEBUGGABLE）；
     * 未注册时视为 debug（全部输出），JVM 桌面无需注册。
     */
    private var isDebugProvider: () -> Boolean = { true }

    private val scopes = CopyOnWriteArraySet<String>()
    private var backend: Backend = defaultBackend

    /** 注册 debug 构建判定源。 */
    fun setDebugProvider(provider: () -> Boolean) {
        isDebugProvider = provider
    }

    /** 整体替换当前后端。未 install 时使用平台默认后端。 */
    fun install(backend: Backend) {
        this.backend = backend
    }

    /** 添加 scope 规则：精确 TAG（如 "LLMController"）或尾部带 `*` 的前缀（如 "nexus.*"）。 */
    fun enableScope(scope: String) {
        scopes.add(scope)
    }

    fun disableScope(scope: String) {
        scopes.remove(scope)
    }

    fun clearScopes() {
        scopes.clear()
    }

    fun v(tag: String, msg: String) {
        if (!isDebugProvider()) return
        log(Level.VERBOSE, tag, msg, null)
    }

    fun d(tag: String, msg: String, logInRelease: Boolean = false) {
        if (!logInRelease && !isDebugProvider()) return
        log(Level.DEBUG, tag, msg, null)
    }

    fun i(tag: String, msg: String) {
        log(Level.INFO, tag, msg, null)
    }

    fun w(tag: String, msg: String) {
        log(Level.WARN, tag, msg, null)
    }

    fun w(tag: String, msg: String, t: Throwable) {
        log(Level.WARN, tag, msg, t)
    }

    fun e(tag: String, msg: String) {
        log(Level.ERROR, tag, msg, null)
    }

    fun e(tag: String, msg: String, t: Throwable) {
        log(Level.ERROR, tag, msg, t)
    }

    private fun log(level: Level, tag: String, msg: String, throwable: Throwable?) {
        if (level.priority < this.level.priority) return
        if (!scopeAllowed(tag)) return
        backend.emit(level, tag, msg, throwable)
    }

    private fun scopeAllowed(tag: String): Boolean {
        if (scopes.isEmpty()) return true
        return scopes.any { scope ->
            if (scope.endsWith("*")) {
                tag.startsWith(scope.dropLast(1))
            } else {
                tag == scope
            }
        }
    }
}
