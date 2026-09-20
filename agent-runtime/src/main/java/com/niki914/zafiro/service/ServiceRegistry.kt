package com.niki914.zafiro.service

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * 进程内服务注册表：业务方按接口类型取用实现，不在调用点依赖实现类。
 *
 * 装配点按进程划分（主进程 `App.onCreate`，宿主进程 `onLoad`）。
 * 键是接口的 [KClass]，注册实现类无法按接口取出；同一接口单实现，重复注册为覆盖。
 * 跨进程不共享实例：宿主进程要取同一能力时由该进程注册 Binder 代理实现。
 */
object ServiceRegistry {
    private val services = ConcurrentHashMap<KClass<*>, Any>()

    fun install(type: KClass<*>, service: Any) {
        services[type] = service
    }

    fun find(type: KClass<*>): Any? = services[type]

    /** 单测复位：对象状态跨用例保留。 */
    fun clearForTest() {
        services.clear()
    }
}

/** 装配服务，键为接口类型（`installService<IHomeChat>(impl)`）。 */
inline fun <reified T : Any> installService(service: T) {
    ServiceRegistry.install(T::class, service)
}

/**
 * 取服务。未安装时抛错：写命令路径用这个，避免 `get()?.stop()` 静默无操作。
 * 需要可空语义时另加 `getService`，当前无调用方（YAGNI）。
 */
inline fun <reified T : Any> requireService(): T =
    ServiceRegistry.find(T::class) as? T
        ?: error("Service ${T::class.qualifiedName} is not installed")
