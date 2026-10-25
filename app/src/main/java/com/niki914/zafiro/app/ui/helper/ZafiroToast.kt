package com.niki914.zafiro.app.ui.helper

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.business.application.ApplicationService
import com.niki914.zafiro.service.requireService

/**
 * 统一即时 Toast：
 * - 线程保护：在主线程直接同步展示，在子线程通过主线程 Handler 调度执行，防止 Looper 缺失崩溃；
 * - Crash 保护：全链路使用 runCatchingCancellable 拦截，防止系统端或服务未注入异常；
 * - 即时打断：主动 [cancel] 排队中的提示，避免连续点击排队滞后；
 * - 语言同步：优先根据 [AppCompatDelegate.getApplicationLocales] 渲染文案，严格遵循应用内选定语言。
 */
internal object ZafiroToast {
    private val mainHandler by lazy(LazyThreadSafetyMode.NONE) {
        Handler(Looper.getMainLooper())
    }

    private var activeToast: Toast? = null

    fun show(@StringRes resId: Int, duration: Int = Toast.LENGTH_SHORT) {
        runOnMainThread {
            runCatchingCancellable {
                val appService = requireService<ApplicationService>()
                val context = appService.getActivity() ?: appService.getApplication()
                val text = resolveLocalizedString(context, resId)
                showInternal(context, text, duration)
            }
        }
    }

    fun show(text: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
        runOnMainThread {
            runCatchingCancellable {
                val appService = requireService<ApplicationService>()
                val context = appService.getActivity() ?: appService.getApplication()
                showInternal(context, text, duration)
            }
        }
    }

    private fun runOnMainThread(action: () -> Unit) {
        val mainLooper = Looper.getMainLooper() ?: return
        if (Looper.myLooper() == mainLooper) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun showInternal(context: Context, text: CharSequence, duration: Int) {
        runCatchingCancellable {
            activeToast?.cancel()
            val toast = Toast.makeText(context.applicationContext, text, duration)
            activeToast = toast
            toast.show()
        }
    }

    private fun resolveLocalizedString(context: Context, @StringRes resId: Int): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return context.getString(resId)
        val targetLocale = locales[0] ?: return context.getString(resId)
        val config = Configuration(context.resources.configuration).apply {
            setLocale(targetLocale)
        }
        return context.createConfigurationContext(config).getString(resId)
    }
}
