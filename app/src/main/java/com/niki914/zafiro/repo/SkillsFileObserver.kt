package com.niki914.zafiro.repo

import android.os.Build
import android.os.FileObserver
import com.niki914.zafiro.api.util.Debouncer
import com.niki914.zafiro.api.util.tryIgnore
import com.niki914.zafiro.api.util.tryOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 监控 skills 目录及其直接子目录文件变更的文件观察器。
 *
 * 当 Agent 通过终端命令、Python 脚本或外部进程写入/删除/修改 SKILL.md 时，
 * 通过 Linux inotify 事件感知文件变更，并经由防抖触发 [onChanged] 回调。
 */
internal class SkillsFileObserver(
    private val skillsRoot: File,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    debounceDelayMs: Long = 300L,
    private val onChanged: suspend () -> Unit,
) {
    private val debouncer = Debouncer(scope, defaultDelayMs = debounceDelayMs)
    private val activeObservers = ConcurrentHashMap<String, FileObserver>()

    @Volatile
    private var isWatching = false

    fun startWatching() {
        if (isWatching) return
        isWatching = true
        rescanAndWatch()
    }

    fun stopWatching() {
        isWatching = false
        debouncer.cancel()
        for (observer in activeObservers.values) {
            tryIgnore { observer.stopWatching() }
        }
        activeObservers.clear()
    }

    private fun rescanAndWatch() {
        if (!isWatching) return
        if (!skillsRoot.exists()) {
            skillsRoot.mkdirs()
        }

        val dirsToWatch = mutableListOf<File>()
        dirsToWatch.add(skillsRoot)

        val firstLevel = skillsRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
        for (first in firstLevel) {
            dirsToWatch.add(first)
            val secondLevel = first.listFiles()?.filter { it.isDirectory }.orEmpty()
            for (second in secondLevel) {
                dirsToWatch.add(second)
            }
        }

        val currentPaths = dirsToWatch.mapNotNull { tryOrNull { it.canonicalPath } ?: it.absolutePath }.toSet()

        val iterator = activeObservers.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in currentPaths) {
                tryIgnore { entry.value.stopWatching() }
                iterator.remove()
            }
        }

        for (dir in dirsToWatch) {
            val path = tryOrNull { dir.canonicalPath } ?: dir.absolutePath
            if (!activeObservers.containsKey(path)) {
                val observer = createObserver(dir)
                activeObservers[path] = observer
                tryIgnore { observer.startWatching() }
            }
        }
    }

    private fun createObserver(dir: File): FileObserver {
        val onEventCallback: (Int, String?) -> Unit = { event, _ ->
            val eventType = event and FileObserver.ALL_EVENTS
            if (eventType and RELEVANT_EVENTS != 0) {
                debouncer.debounce {
                    rescanAndWatch()
                    onChanged()
                }
            }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, RELEVANT_EVENTS) {
                override fun onEvent(event: Int, path: String?) {
                    onEventCallback(event, path)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(dir.absolutePath, RELEVANT_EVENTS) {
                override fun onEvent(event: Int, path: String?) {
                    onEventCallback(event, path)
                }
            }
        }
    }

    companion object {
        private const val RELEVANT_EVENTS = FileObserver.CREATE or
                FileObserver.DELETE or
                FileObserver.CLOSE_WRITE or
                FileObserver.MOVED_TO or
                FileObserver.MOVED_FROM or
                FileObserver.DELETE_SELF or
                FileObserver.MOVE_SELF
    }
}
