package com.niki914.nexus.xposed.api.util

import android.content.Context

/**
 * 负责在目标应用冷启动阶段跨线程、跨层级地提供并挂起等待 Context
 */
object ContextProvider : XProvider<Context>()