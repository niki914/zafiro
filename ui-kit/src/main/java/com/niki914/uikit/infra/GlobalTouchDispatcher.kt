package com.niki914.uikit.infra

import android.view.MotionEvent

fun interface GlobalTouchInterceptor {
    fun onDispatchTouchEvent(event: MotionEvent)
}

object GlobalTouchDispatcher {
    private val interceptors = mutableListOf<GlobalTouchInterceptor>()

    fun register(interceptor: GlobalTouchInterceptor): () -> Unit {
        interceptors.add(interceptor)
        return {
            interceptors.remove(interceptor)
        }
    }

    fun dispatch(event: MotionEvent) {
        if (interceptors.isEmpty()) return
        val currentInterceptors = interceptors.toList()
        for (interceptor in currentInterceptors) {
            interceptor.onDispatchTouchEvent(event)
        }
    }
}
