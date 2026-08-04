package com.niki914.libterm.backend.shizuku

import com.niki914.libterm.AuthorizationResult
import com.niki914.libterm.PrivilegeAuthorizer
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.backend.shizuku.internal.RealShizukuPermissionRequester
import com.niki914.libterm.backend.shizuku.internal.ShizukuPermissionRequester
import com.niki914.libterm.backend.shizuku.internal.ShizukuPermissionResultListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

class ShizukuPrivilegeAuthorizer internal constructor(
    private val permissionRequester: ShizukuPermissionRequester,
    private val requestCodeGenerator: () -> Int,
    private val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
) : PrivilegeAuthorizer {
    public constructor() : this(
        permissionRequester = RealShizukuPermissionRequester(),
        requestCodeGenerator = { nextRequestCode.getAndIncrement() },
    )

    override suspend fun requestAuthorization(identity: TerminalIdentity): AuthorizationResult {
        if (identity != TerminalIdentity.Shizuku) {
            return AuthorizationResult.Unavailable(
                TerminalFailure.BackendUnavailable(
                    identity = identity,
                    message = "Shizuku authorizer only supports SHIZUKU",
                ),
            )
        }

        return try {
            requestShizukuAuthorization()
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }
            authorizationFailed(message = error.message, cause = error)
        }
    }

    private suspend fun requestShizukuAuthorization(): AuthorizationResult {
        if (!permissionRequester.isBinderAlive()) {
            return AuthorizationResult.Unavailable(
                TerminalFailure.BackendUnavailable(
                    identity = TerminalIdentity.Shizuku,
                    message = "Shizuku is not installed or not running",
                ),
            )
        }
        if (permissionRequester.isPermissionGranted()) {
            return AuthorizationResult.Granted
        }

        val requestCode = requestCodeGenerator()
        return try {
            withTimeout(requestTimeoutMillis) {
                awaitAuthorizationResult(requestCode)
            }
        } catch (_: TimeoutCancellationException) {
            if (permissionRequester.isPermissionGranted()) {
                AuthorizationResult.Granted
            } else {
                authorizationFailed(message = "Timed out waiting for Shizuku authorization result")
            }
        }
    }

    private suspend fun awaitAuthorizationResult(requestCode: Int): AuthorizationResult {
        return suspendCancellableCoroutine { continuation ->
            lateinit var listener: ShizukuPermissionResultListener
            listener = ShizukuPermissionResultListener { callbackRequestCode, granted ->
                if (callbackRequestCode != requestCode || !continuation.isActive) {
                    return@ShizukuPermissionResultListener
                }
                permissionRequester.removeRequestPermissionResultListener(listener)
                continuation.resume(
                    if (granted) {
                        AuthorizationResult.Granted
                    } else {
                        AuthorizationResult.Denied(
                            TerminalFailure.AuthorizationDenied(
                                identity = TerminalIdentity.Shizuku,
                                message = "Shizuku authorization was denied",
                            ),
                        )
                    },
                )
            }

            continuation.invokeOnCancellation {
                permissionRequester.removeRequestPermissionResultListener(listener)
            }

            try {
                permissionRequester.addRequestPermissionResultListener(listener)
                permissionRequester.requestPermission(requestCode)
            } catch (error: Throwable) {
                permissionRequester.removeRequestPermissionResultListener(listener)
                if (continuation.isActive) {
                    continuation.resume(authorizationFailed(message = error.message, cause = error))
                }
            }
        }
    }

    private fun authorizationFailed(
        message: String?,
        cause: Throwable? = null,
    ): AuthorizationResult.Failed {
        return AuthorizationResult.Failed(
            TerminalFailure.AuthorizationFailed(
                identity = TerminalIdentity.Shizuku,
                message = message,
                cause = cause,
            ),
        )
    }

    private companion object {
        private const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 15_000L
        private val nextRequestCode = AtomicInteger(1)
    }
}
