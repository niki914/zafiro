package com.niki914.libterm.backend.libsu

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface LibsuRootAccessChecker {
    suspend fun checkRootAccess(): LibsuRootAccessResult
}

internal sealed interface LibsuRootAccessResult {
    data object Available : LibsuRootAccessResult

    data class Unavailable(val message: String? = null) : LibsuRootAccessResult

    data class Unauthorized(val message: String? = null) : LibsuRootAccessResult
}

internal class RealLibsuRootAccessChecker(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LibsuRootAccessChecker {
    override suspend fun checkRootAccess(): LibsuRootAccessResult = withContext(ioDispatcher) {
        runCatching {
            when (Shell.isAppGrantedRoot()) {
                true -> LibsuRootAccessResult.Available
                false -> LibsuRootAccessResult.Unauthorized("Root authorization was denied")
                null -> LibsuRootAccessResult.Available
            }
        }.getOrElse { throwable ->
            LibsuRootAccessResult.Unavailable(throwable.message)
        }
    }
}
