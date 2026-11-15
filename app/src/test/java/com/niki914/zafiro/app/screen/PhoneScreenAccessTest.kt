// 保护：主屏读/点/截图在服务未连接时会先拦住申请权限；删掉门之后工具会直接报空树，不再问用户。
package com.niki914.zafiro.app.screen

import com.niki914.xsettings.XSettings
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.screen.ActResult
import com.niki914.zafiro.api.screen.ScreenAction
import com.niki914.zafiro.api.screen.ScreenImageUnavailable
import com.niki914.zafiro.api.screen.ScreenTreeUnavailable
import com.niki914.zafiro.api.screen.ShowResult
import com.niki914.zafiro.api.screen.ShowTarget
import com.niki914.zafiro.business.permission.Channel
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionResult
import com.niki914.zafiro.business.permission.PermissionScope
import com.niki914.zafiro.business.permission.PermissionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PhoneScreenAccessTest {

    @Test
    fun connectedServiceSkipsConsentAndRequests() = runTest {
        withTimeout(2_000) {
            val permissions = permissions(granted = false)
            var asked = false
            val access = access(
                permissions = permissions,
                askConsent = {
                    asked = true
                    ApprovalDecision.Allow
                },
                connected = { true },
            )

            assertNull(access.prepare(PhoneScreenAccess.Need.Control))
            assertTrue(permissions.requested.isEmpty())
            assertTrue(!asked)
        }
    }

    @Test
    fun declinedConsentDoesNotRequestPermissions() = runTest {
        withTimeout(2_000) {
            val permissions = permissions(granted = false)
            val settings = MemorySettings(granted = false)
            var waited = false
            val access = access(
                permissions = permissions,
                settings = settings,
                askConsent = { ApprovalDecision.Deny },
                connected = { false },
                waitUntilConnected = {
                    waited = true
                    true
                },
            )

            val denial = access.prepare(PhoneScreenAccess.Need.Control)
            assertEquals("CONSENT_DENIED", denial?.code)
            assertTrue(permissions.requested.isEmpty())
            assertTrue(!settings.phoneUseConsentGranted.value)
            assertTrue(!waited)
        }
    }

    @Test
    fun allowedConsentRequestsAccessibilityAndOverlay() = runTest {
        withTimeout(2_000) {
            val permissions = permissions(granted = false, grantOnRequest = true)
            val settings = MemorySettings(granted = false)
            val access = access(
                permissions = permissions,
                settings = settings,
                askConsent = { ApprovalDecision.Allow },
                connected = { false },
                waitUntilConnected = { true },
            )

            assertNull(access.prepare(PhoneScreenAccess.Need.Control))
            assertEquals(
                listOf(Permission.ACCESSIBILITY, Permission.OVERLAY),
                permissions.requested,
            )
            assertTrue(settings.phoneUseConsentGranted.value)
        }
    }

    @Test
    fun captureRequestsOnlyAccessibility() = runTest {
        withTimeout(2_000) {
            val permissions = permissions(granted = false, grantOnRequest = true)
            var asked = false
            val access = access(
                permissions = permissions,
                askConsent = {
                    asked = true
                    ApprovalDecision.Deny
                },
                connected = { false },
                waitUntilConnected = { true },
            )

            assertNull(access.prepare(PhoneScreenAccess.Need.Capture))
            assertEquals(listOf(Permission.ACCESSIBILITY), permissions.requested)
            assertTrue(!asked)
        }
    }

    @Test
    fun grantedButUnboundServiceIsNotTreatedAsReady() = runTest {
        withTimeout(2_000) {
            val permissions = permissions(granted = true)
            val access = access(
                permissions = permissions,
                connected = { false },
                waitUntilConnected = { false },
            )

            val denial = access.prepare(PhoneScreenAccess.Need.Control)
            assertEquals("SERVICE_NOT_CONNECTED", denial?.code)
            assertTrue(permissions.requested.isEmpty())
        }
    }

    @Test
    fun screenReadAndTapStopAtTheGate() = runTest {
        withTimeout(2_000) {
            val denial = PhoneScreenDenial("CONSENT_DENIED", "blocked-by-gate")
            val access = StubAccess(denial)
            val screen = PhoneDisplayScreen(access = access)

            try {
                screen.snapshot()
                fail("snapshot should stop at the gate")
            } catch (error: ScreenTreeUnavailable) {
                assertEquals("blocked-by-gate", error.message)
            }

            try {
                screen.image()
                fail("screenshot should stop at the gate")
            } catch (error: ScreenImageUnavailable) {
                assertEquals("CONSENT_DENIED", error.code)
                assertEquals("blocked-by-gate", error.message)
            }

            val acted = screen.act(ScreenAction.Key(4))
            assertEquals(ActResult.Failed("CONSENT_DENIED", null), acted)

            val showed = screen.show(ShowTarget.AppName("test-app"))
            assertEquals(ShowResult.Failed("CONSENT_DENIED"), showed)

            assertEquals(
                listOf(
                    PhoneScreenAccess.Need.Control,
                    PhoneScreenAccess.Need.Capture,
                    PhoneScreenAccess.Need.Control,
                    PhoneScreenAccess.Need.OpenApp,
                ),
                access.needs,
            )
        }
    }

    @Test
    fun captureWithoutConsentStopsAtConsentGate() = runTest {
        withTimeout(2_000) {
            val permissions = permissions(granted = false)
            val settings = MemorySettings(granted = false)
            val access = access(
                permissions = permissions,
                settings = settings,
                askConsent = { ApprovalDecision.Deny },
                connected = { false },
            )

            val denial = access.prepare(PhoneScreenAccess.Need.Capture)
            assertEquals("CONSENT_DENIED", denial?.code)
            assertTrue(permissions.requested.isEmpty())
        }
    }

    @Test
    fun openAppWithoutConsentStopsAtConsentGate() = runTest {
        withTimeout(2_000) {
            val permissions = permissions(granted = false)
            val settings = MemorySettings(granted = false)
            val access = access(
                permissions = permissions,
                settings = settings,
                askConsent = { ApprovalDecision.Deny },
                connected = { false },
            )

            val denial = access.prepare(PhoneScreenAccess.Need.OpenApp)
            assertEquals("CONSENT_DENIED", denial?.code)
            assertTrue(permissions.requested.isEmpty())
        }
    }

    @Test
    fun openAppWithConsentPassesWithoutDriverConnection() = runTest {
        withTimeout(2_000) {
            val permissions = permissions(granted = false)
            val settings = MemorySettings(granted = false)
            val access = access(
                permissions = permissions,
                settings = settings,
                askConsent = { ApprovalDecision.Allow },
                connected = { false },
            )

            assertNull(access.prepare(PhoneScreenAccess.Need.OpenApp))
            assertTrue(settings.phoneUseConsentGranted.value)
            assertTrue(permissions.requested.isEmpty())
        }
    }

    @Test
    fun virtualDisplayRequestsConsentAndPermissionsOnApproval() = runTest {
        withTimeout(2_000) {
            val permissions = permissions(granted = false, grantOnRequest = true)
            val settings = MemorySettings(granted = false)
            val access = access(
                permissions = permissions,
                settings = settings,
                askConsent = { ApprovalDecision.Allow },
                connected = { false },
                waitUntilConnected = { true },
            )

            assertNull(access.prepare(PhoneScreenAccess.Need.VirtualDisplay))
            assertEquals(
                listOf(Permission.ACCESSIBILITY, Permission.OVERLAY),
                permissions.requested,
            )
            assertTrue(settings.phoneUseConsentGranted.value)
        }
    }

    @Test
    fun connectedServiceWithoutConsentStillAsksConsent() = runTest {
        withTimeout(2_000) {
            val permissions = permissions(granted = false)
            val settings = MemorySettings(granted = false)
            var asked = false
            val access = access(
                permissions = permissions,
                settings = settings,
                askConsent = {
                    asked = true
                    ApprovalDecision.Allow
                },
                connected = { true },
            )

            assertNull(access.prepare(PhoneScreenAccess.Need.Control))
            assertTrue(asked)
            assertTrue(settings.phoneUseConsentGranted.value)
        }
    }

    private fun access(
        permissions: RecordingPermissions,
        settings: MemorySettings = MemorySettings(granted = true),
        askConsent: suspend () -> ApprovalDecision = { ApprovalDecision.Deny },
        connected: () -> Boolean,
        waitUntilConnected: suspend () -> Boolean = { false },
    ): PhoneScreenAccess = PhoneScreenAccess(
        permissions = { permissions },
        settings = { settings },
        askConsent = askConsent,
        connected = connected,
        waitUntilConnected = waitUntilConnected,
    )

    private fun permissions(
        granted: Boolean,
        grantOnRequest: Boolean = false,
    ): RecordingPermissions = RecordingPermissions(
        initial = if (granted) PermissionState.GRANTED else PermissionState.DENIED_BY_USER,
        grantOnRequest = grantOnRequest,
    )

    private class RecordingPermissions(
        initial: PermissionState,
        private val grantOnRequest: Boolean,
    ) : PermissionManager {
        private val states = Permission.entries.associateWith { initial }.toMutableMap()
        val requested = mutableListOf<Permission>()

        override fun status(permission: Permission): PermissionState = states.getValue(permission)

        override suspend fun request(permission: Permission): PermissionResult {
            requested += permission
            val next = if (grantOnRequest) PermissionState.GRANTED else PermissionState.DENIED_BY_USER
            states[permission] = next
            return PermissionResult(permission, next, emptyList())
        }

        override suspend fun request(
            permission: Permission,
            vararg channels: Channel,
        ): PermissionResult = request(permission)

        override fun applyScope(vararg channels: Channel): PermissionScope =
            throw UnsupportedOperationException("not used")
    }

    private class MemorySettings(granted: Boolean) : XSettings {
        override val floatingBallAutoExpand = MutableStateFlow(false)
        override val appLanguageTag = MutableStateFlow("")
        override val phoneUseConsentGranted = MutableStateFlow(granted)
        override suspend fun setPhoneUseConsentGranted(granted: Boolean) {
            phoneUseConsentGranted.value = granted
        }
    }

    private class StubAccess(
        private val denial: PhoneScreenDenial,
    ) : PhoneScreenAccess(
        permissions = { error("unused") },
        settings = { null },
        askConsent = { error("unused") },
        connected = { false },
        waitUntilConnected = { false },
    ) {
        val needs = mutableListOf<PhoneScreenAccess.Need>()

        override suspend fun prepare(need: PhoneScreenAccess.Need): PhoneScreenDenial {
            needs += need
            return denial
        }
    }
}
