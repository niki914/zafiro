package com.niki914.shizuku.privileged

import android.os.IBinder
import com.niki914.logging.Logger
import com.niki914.shizuku.internal.runCatchingCancellable

/**
 * 宿主进程存活监听器：绑定主进程 Token，主进程异常退出时触发清理回调。
 */
class HostDeathWatcher(
    private val onHostDied: () -> Unit,
) {
    private var linkedToken: IBinder? = null

    private val deathRecipient = IBinder.DeathRecipient {
        Logger.w(TAG, "Host process died, triggering teardown")
        onHostDied()
    }

    fun linkTo(token: IBinder?) {
        if (token == null) return
        unlink()
        linkedToken = token
        runCatchingCancellable {
            token.linkToDeath(deathRecipient, 0)
        }
    }

    fun unlink() {
        linkedToken?.let { token ->
            runCatchingCancellable { token.unlinkToDeath(deathRecipient, 0) }
        }
        linkedToken = null
    }

    companion object {
        private const val TAG = "niki914_zafiro_HostDeathWatcher"
    }
}
