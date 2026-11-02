package com.niki914.zafiro.business.remote.activity

import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.niki914.logging.Logger
import com.niki914.zafiro.api.telemetry.EventDef
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.business.remote.ledger.Ledger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 主进程启动，以及进程回到前台时，把当天交给账本记为活跃。
 * 没有界面、只在后台被拉起时，[attach] 同样记当天。
 */
internal class ActivityTracker(
    private val ledger: Ledger,
    private val versionCode: Int,
    private val scope: CoroutineScope,
    private val onActive: (suspend () -> Unit)? = null,
    private val onColdStart: (suspend () -> Unit)? = null,
    private val telemetryEnabledProvider: suspend () -> Boolean = { true },
) : DefaultLifecycleObserver {
    private var observing = false
    private var isColdStart = true
    private var foregroundStartMs = 0L

    @MainThread
    fun attach() {
        markToday()
        scope.launch {
            runCatchingCancellable { onColdStart?.invoke() }
                .onFailure { error -> Logger.w(LOG_TAG, "冷启动回调执行失败", error) }
        }
        if (observing) return
        val registered = runCatchingCancellable {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }
        registered.onFailure { error -> Logger.w(LOG_TAG, "前台监听注册失败", error) }
        if (registered.isSuccess) observing = true
    }

    override fun onStart(owner: LifecycleOwner) {
        markToday()
        foregroundStartMs = SystemClock.elapsedRealtime()
        if (isColdStart) {
            isColdStart = false
        } else {
            scope.launch {
                if (!telemetryEnabledProvider()) return@launch
                runCatchingCancellable {
                    ledger.record(
                        event = EventDef.AppForeground.wireName,
                        label = null,
                        n = 1,
                        custom = emptyMap(),
                        versionCode = versionCode,
                    )
                }
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        val start = foregroundStartMs
        foregroundStartMs = 0L
        if (start > 0L) {
            val seconds = ((SystemClock.elapsedRealtime() - start) / 1000L).toInt()
            if (seconds > 0) {
                scope.launch {
                    if (!telemetryEnabledProvider()) return@launch
                    runCatchingCancellable {
                        ledger.record(
                            event = EventDef.AppActiveDuration.wireName,
                            label = null,
                            n = seconds,
                            custom = emptyMap(),
                            versionCode = versionCode,
                        )
                    }
                }
            }
        }
    }

    private fun markToday() {
        scope.launch {
            val result = runCatchingCancellable { ledger.markActive(versionCode) }
            result.onFailure { error -> Logger.w(LOG_TAG, "活跃日记账失败", error) }
            if (result.isSuccess) {
                onActive?.invoke()
            }
        }
    }

    private companion object {
        const val LOG_TAG = "niki914_zafiro_ActivityTracker"
    }
}
