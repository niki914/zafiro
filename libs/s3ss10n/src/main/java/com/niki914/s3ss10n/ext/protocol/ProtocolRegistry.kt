package com.niki914.s3ss10n.ext.protocol

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.reflect.KClass

object ProtocolRegistry {
    private val map = mutableMapOf<KClass<out ChatProtocol>, ChatProtocol>()
    private val mutex = Mutex()

    suspend fun <P : ChatProtocol> register(klass: KClass<P>, instance: P) {
        mutex.withLock { map[klass] = instance }
    }

    suspend fun resolve(klass: KClass<out ChatProtocol>): ChatProtocol {
        return mutex.withLock {
            map[klass] ?: throw IllegalStateException(
                "Protocol `${klass.qualifiedName}` is not registered. " +
                    "Call ProtocolRegistry.register(${klass.simpleName}::class, ${klass.simpleName}()) first."
            )
        }
    }
}
