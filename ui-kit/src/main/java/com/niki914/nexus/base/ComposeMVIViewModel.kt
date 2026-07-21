package com.niki914.nexus.base

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * MVI 架构的 ViewModel 基类 - Compose 优化版
 *
 * @param Intent 用户意图/行为
 * @param State UI状态
 * @param Effect 一次性效果
 */
abstract class ComposeMVIViewModel<Intent, State, Effect>(
) : ViewModel() {

    /**
     * 只读 state flow，若在 compose 中可直接用 uiState
     */
    val uiStateFlow: StateFlow<State> by lazy {
        _uiStateFlow.asStateFlow()
    }

    /**
     * 获取当前状态
     */
    protected val currentState: State
        get() = uiStateFlow.value

    // 副作用 - 用于一次性事件（导航、Toast、SnackBar等）
    private val _uiEffect = MutableSharedFlow<Effect>(
        extraBufferCapacity = 1 // 防止丢失effect
    )

    val uiEffect: SharedFlow<Effect> = _uiEffect.asSharedFlow()

    // Intent处理通道
    private val intentChannel = Channel<Intent>(Channel.UNLIMITED)

    init {
        handleIntents()
    }

    // 处理Intent流
    private fun handleIntents() {
        viewModelScope.launch {
            intentChannel.consumeAsFlow().collect { intent ->
                try {
                    handleIntent(intent)
                } catch (e: Exception) {
                    onError(e)
                }
            }
        }
    }

    /**
     * 发送用户意图
     * Compose中直接调用：viewModel.sendIntent(SomeIntent)
     */
    fun sendIntent(intent: Intent) {
        viewModelScope.launch {
            intentChannel.trySend(intent).getOrThrow()
        }
    }

    /**
     * 更新UI状态
     * 使用copy语法：updateState { copy(loading = true) }
     */
    protected fun updateState(update: State.() -> State) {
        _uiStateFlow.update(update)
    }

    /**
     * 发送副作用
     * 用于一次性事件：sendEffect(ShowToast("成功"))
     */
    protected fun sendEffect(effect: Effect) {
        viewModelScope.launch {
            _uiEffect.emit(effect)
        }
    }

    /**
     * 错误处理 - 子类可重写自定义错误处理
     */
    protected open fun onError(error: Throwable) {
        Log.e(
            this::class.simpleName ?: "ComposeMVIViewModel",
            error.message ?: "Unhandled intent error",
            error
        )
    }

    // 抽象方法
    protected abstract fun initUiState(): State
    protected abstract suspend fun handleIntent(intent: Intent)

    private val _uiStateFlow = MutableStateFlow(initUiState())
}
