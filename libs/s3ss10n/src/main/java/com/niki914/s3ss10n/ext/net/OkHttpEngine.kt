package com.niki914.s3ss10n.ext.net

import com.niki914.s3ss10n.net.HttpEngine
import com.niki914.s3ss10n.net.HttpFrame
import com.niki914.s3ss10n.net.HttpRequest
import com.niki914.s3ss10n.net.SseLineParser
import com.niki914.s3ss10n.xTry
import com.niki914.s3ss10n.xTrySuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class OkHttpEngine : HttpEngine {
    private val client = OkHttpClient.Builder().build()
    private val activeCalls = Collections.synchronizedSet(mutableSetOf<Call>())

    override fun stream(request: HttpRequest): Flow<String> = callbackFlow {
        xTrySuspend("OkHttpEngine.stream", onError = { e ->
            close(e)
        }) {
            val callClient = client.newBuilder()
                .connectTimeout(request.timeoutMs.connectMs, TimeUnit.MILLISECONDS)
                .readTimeout(request.timeoutMs.readMs, TimeUnit.MILLISECONDS)
                .writeTimeout(request.timeoutMs.writeMs, TimeUnit.MILLISECONDS)
                .build()

            val requestBody = request.body?.toRequestBody("application/json".toMediaTypeOrNull())
            val okHttpRequest = Request.Builder()
                .url(request.url)
                .method(request.method, requestBody)
                .headers(request.headers.toHeaders())
                .build()

            val call = callClient.newCall(okHttpRequest)
            activeCalls.add(call)
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    activeCalls.remove(call)
                    close(e)
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    xTry("OkHttpEngine.stream.onResponse") {
                        response.use { currentResponse ->
                            if (!currentResponse.isSuccessful) {
                                val responseBody = currentResponse.body?.string()?.trim().orEmpty()
                                val bodySuffix = if (responseBody.isEmpty()) {
                                    ""
                                } else {
                                    ", body=$responseBody"
                                }
                                close(
                                    IllegalStateException(
                                        "HTTP ${currentResponse.code} ${currentResponse.message}$bodySuffix"
                                    )
                                )
                                return
                            }
                            val body = currentResponse.body ?: run {
                                close(IllegalStateException("Response body is null"))
                                return
                            }
                            body.charStream().buffered().useLines { lines ->
                                lines.forEach { line -> trySend(line) }
                            }
                            close()
                        }
                    }
                    activeCalls.remove(call)
                }
            })

            awaitClose {
                call.cancel()
                activeCalls.remove(call)
            }
        }
    }

    override fun frames(request: HttpRequest): Flow<HttpFrame> = callbackFlow {
        xTrySuspend("OkHttpEngine.frames", onError = { e ->
            close(e)
        }) {
            val callClient = client.newBuilder()
                .connectTimeout(request.timeoutMs.connectMs, TimeUnit.MILLISECONDS)
                .readTimeout(request.timeoutMs.readMs, TimeUnit.MILLISECONDS)
                .writeTimeout(request.timeoutMs.writeMs, TimeUnit.MILLISECONDS)
                .build()

            val requestBody = request.body?.toRequestBody("application/json".toMediaTypeOrNull())
            val okHttpRequest = Request.Builder()
                .url(request.url)
                .method(request.method, requestBody)
                .headers(request.headers.toHeaders())
                .build()

            val call = callClient.newCall(okHttpRequest)
            activeCalls.add(call)
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    activeCalls.remove(call)
                    close(e)
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    launch(Dispatchers.IO) {
                        try {
                            xTrySuspend("OkHttpEngine.frames.onResponse", onError = { t ->
                                close(t)
                            }) {
                                response.use { currentResponse ->
                                    if (!currentResponse.isSuccessful) {
                                        val responseBody = currentResponse.body?.string()?.trim().orEmpty()
                                        val bodySuffix = if (responseBody.isEmpty()) {
                                            ""
                                        } else {
                                            ", body=$responseBody"
                                        }
                                        close(
                                            IllegalStateException(
                                                "HTTP ${currentResponse.code} ${currentResponse.message}$bodySuffix"
                                            )
                                        )
                                        return@xTrySuspend
                                    }
                                    val body = currentResponse.body ?: run {
                                        close(IllegalStateException("Response body is null"))
                                        return@xTrySuspend
                                    }
                                    val contentType = currentResponse.header("Content-Type").orEmpty()
                                    if (contentType.contains("text/event-stream", ignoreCase = true)) {
                                        body.charStream().buffered().useLines { lines ->
                                            val rawLines = flow {
                                                lines.forEach { line -> emit(line) }
                                            }
                                            SseLineParser.parseEvents(rawLines).collect { event ->
                                                trySend(HttpFrame.SseData(event.data, event.event))
                                            }
                                        }
                                    } else {
                                        trySend(HttpFrame.Text(body.string()))
                                    }
                                    close()
                                }
                            }
                        } finally {
                            activeCalls.remove(call)
                        }
                    }
                }
            })

            awaitClose {
                call.cancel()
                activeCalls.remove(call)
            }
        }
    }

    override suspend fun unary(request: HttpRequest): String = suspendCancellableCoroutine { cont ->
        val callClient = client.newBuilder()
            .connectTimeout(request.timeoutMs.connectMs, TimeUnit.MILLISECONDS)
            .readTimeout(request.timeoutMs.readMs, TimeUnit.MILLISECONDS)
            .writeTimeout(request.timeoutMs.writeMs, TimeUnit.MILLISECONDS)
            .build()
        val requestBody = request.body?.toRequestBody("application/json".toMediaTypeOrNull())
        val okHttpRequest = Request.Builder()
            .url(request.url)
            .method(request.method, requestBody)
            .headers(request.headers.toHeaders())
            .build()
        val call = callClient.newCall(okHttpRequest)
        activeCalls.add(call)
        cont.invokeOnCancellation {
            call.cancel()
            activeCalls.remove(call)
        }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                activeCalls.remove(call)
                if (cont.isActive) {
                    cont.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                xTry("OkHttpEngine.unary", onError = { t ->
                    if (cont.isActive) cont.resumeWithException(t)
                }) {
                    response.use { currentResponse ->
                        val body = currentResponse.body?.string().orEmpty()
                        if (!currentResponse.isSuccessful) {
                            throw IllegalStateException(
                                "HTTP ${currentResponse.code} ${currentResponse.message}, body=${body.trim()}"
                            )
                        }
                        if (cont.isActive) {
                            cont.resume(body)
                        }
                    }
                }
                activeCalls.remove(call)
            }
        })
    }

    override fun close() {
        xTry("OkHttpEngine.close") {
            activeCalls.toList().forEach { it.cancel() }
            activeCalls.clear()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
