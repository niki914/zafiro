package com.niki914.okia.transport

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 默认 HttpEngine：OkHttp 4 实现。public（D-T2B-4）：构造接受自定义
 * OkHttpClient，供 host 注入 proxy interceptor 等；默认 client 门面自建。
 * 经 OkiaConfig.httpEngine 注入或门面自建；KMP 迁移时本文件进入 jvm/android
 * actual（OkHttp5 或 Ktor 替代，HttpEngine 契约不动）。
 * stream：异步 enqueue 挂起到响应头；2xx → body 分块读字符流经 SseLineParser
 * 切行（SSE 行语义单一来源）；非 2xx 预读全文 → Error；网络错误 / 超时抛异常
 * （Kotlin 取消语义，§8.17）。lines 冷流，collect 取消时 call.cancel() 打断
 * 阻塞读并关闭连接。unary：单请求异步；网络失败返回 status/body 缺失结构
 * （HttpResponse 契约，MCP 等非模型请求使用，不触发 hook）。
 * 超时：每个请求按 HttpRequest.timeouts 克隆 client（连接池 / dispatcher 共享
 * via newBuilder）；readTimeout 为读间隔超时，对慢 SSE 流安全。close() 无操作：
 * OkHttp 4 无主动释放语义（连接池到期自保洁、线程 daemon）。
 * Design source: okia PRD §5.14（KMP actual 点）；okhttp3 API。
 */
class OkHttpEngine(
    private val base: OkHttpClient = OkHttpClient()
) : HttpEngine {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // 空 body（POST 无 body 场景：OkHttp 要求 method 携带 body 或零长 body）
    private val emptyBody = object : RequestBody() {
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = 0
        override fun writeTo(writer: BufferedSink) = Unit
    }

    override suspend fun stream(request: HttpRequest): StreamResponse =
        suspendCancellableCoroutine { cont ->
            val call = clientFor(request.timeouts).newCall(request.toOkHttpRequest())
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // 取消导致的失败不覆盖取消语义
                    if (cont.isCancelled) return
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val headers = response.headersMap()
                        if (response.isSuccessful) {
                            cont.resume(StreamResponse.Ok(response.code, headers, lines(call, response)))
                        } else {
                            val body = response.body?.string() ?: ""
                            response.close()
                            cont.resume(StreamResponse.Error(response.code, headers, body))
                        }
                    } catch (e: Exception) {
                        response.close()
                        cont.resumeWithException(e)
                    }
                }
            })
        }

    override suspend fun unary(request: HttpRequest): HttpResponse =
        suspendCancellableCoroutine { cont ->
            val call = clientFor(request.timeouts).newCall(request.toOkHttpRequest())
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isCancelled) return
                    // 网络失败：status 与 body 缺失（HttpResponse 契约，§8.17）
                    cont.resume(HttpResponse(null, emptyMap(), null))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.body?.bytes()
                        cont.resume(HttpResponse(response.code, response.headersMap(), body))
                    } catch (e: IOException) {
                        // 传输中途失败（超时 / 断连）：status 与 body 缺失（契约 §8.17）；
                        // 只吞 IO 失败，运行时错误（编程错误）保持外抛
                        cont.resume(HttpResponse(null, emptyMap(), null))
                    } finally {
                        response.close()
                    }
                }
            })
        }

    override fun close(): Unit = Unit

    // 按请求超时克隆 client；newBuilder 共享基础 client 的连接池与 dispatcher
    private fun clientFor(timeouts: HttpTimeouts): OkHttpClient =
        base.newBuilder()
            .connectTimeout(timeouts.connectMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeouts.readMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeouts.writeMs, TimeUnit.MILLISECONDS)
            .build()

    // body 行流：分块读 UTF-8 字符 → SseLineParser 切行（行切分与分类单一来源）。
    // 冷流：每次 collect 独立读取。取消路径：阻塞 read 不响应协程取消，故在
    // 构建时注册 job 取消监听 → call.cancel() 打断阻塞读（socket 中断）；
    // finally 兜底 cancel + close。每次读前 ensureActive 提前退出（无阻塞时）。
    private fun lines(call: Call, response: Response): Flow<SseLine> {
        val chunks = flow<String> {
            val context = currentCoroutineContext()
            context[Job]?.invokeOnCompletion { call.cancel() }
            val body = response.body ?: return@flow
            val reader = body.byteStream().bufferedReader()
            val buffer = CharArray(READ_CHUNK_SIZE)
            while (true) {
                context.ensureActive()
                val n = reader.read(buffer)
                if (n == -1) break
                emit(String(buffer, 0, n))
            }
        }
        return flow {
            try {
                // SSE 阻塞读与行切分切到 IO 池：lines 流继承 collector 上下文
                // （生产默认 turnScope = Dispatchers.Default），阻塞 reader.read 会
                // 各占一个 CPU worker 等待网络（P2）；flowOn 只改执行线程，取消
                // 清理（invokeOnCompletion / finally cancel+close / ensureActive）
                // 不依赖线程，照常生效。
                SseLineParser().parse(chunks.flowOn(Dispatchers.IO)).collect { emit(it) }
            } finally {
                call.cancel()
                response.close()
            }
        }
    }

    private fun HttpRequest.toOkHttpRequest(): Request {
        val body = when {
            body != null -> body!!.toRequestBody(jsonMediaType)
            method == "GET" || method == "HEAD" || method == "DELETE" -> null
            else -> emptyBody
        }
        val builder = Request.Builder().url(url).method(method, body)
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    private fun Response.headersMap(): Map<String, String> =
        headers.associate { it.first to it.second }

    private companion object {
        const val READ_CHUNK_SIZE = 8192
    }
}