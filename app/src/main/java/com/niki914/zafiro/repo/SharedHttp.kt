package com.niki914.zafiro.repo

import okhttp3.OkHttpClient

/**
 * 业务层共享 OkHttp 单例（连接池 / 线程池复用）。
 * base 保持直连；需要代理的调用方经 newBuilder clone 隔离，不污染 base。
 * okia 自有 HttpEngine，不动。
 */
// ponytail: 全局共享 base 复用连接池；函数体内禁止 new OkHttpClient()
internal object SharedHttp {
    val client: OkHttpClient by lazy { OkHttpClient() }
}
