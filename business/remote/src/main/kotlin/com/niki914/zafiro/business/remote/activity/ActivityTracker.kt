package com.niki914.zafiro.business.remote.activity

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.niki914.logging.Logger
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
) : DefaultLifecycleObserver {
    private var observing = false

    fun attach() {
        markToday()
        if (observing) return
        val registered = runCatchingCancellable {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }
        registered.onFailure { error -> Logger.w(LOG_TAG, "前台监听注册失败", error) }
        if (registered.isSuccess) observing = true
    }

    override fun onStart(owner: LifecycleOwner) {
        markToday()
    }

    private fun markToday() {
        scope.launch {
            runCatchingCancellable { ledger.markActive(versionCode) }
                .onFailure { error -> Logger.w(LOG_TAG, "活跃日记账失败", error) }
        }
    }

    private companion object {
        const val LOG_TAG = "niki914_zafiro_ActivityTracker"
    }
}
