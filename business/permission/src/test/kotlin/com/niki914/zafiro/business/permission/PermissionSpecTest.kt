package com.niki914.zafiro.business.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 版本分叉的唯一真相表。纯数据，不碰 Android 框架（权限名按字面钉住，与 ShellGrants 的命令同口径）。
 */
class PermissionSpecTest {

    private fun mechanism(permission: Permission, api: Int) =
        PermissionSpec.appLevelMechanism(permission, api)

    @Test
    fun `notification needs no grant below 33`() {
        assertEquals(GrantMechanism.None, mechanism(Permission.NOTIFICATION, 32))
        assertEquals(
            GrantMechanism.Runtime(
                names = listOf("android.permission.POST_NOTIFICATIONS"),
                appOps = listOf("POST_NOTIFICATION"),
            ),
            mechanism(Permission.NOTIFICATION, 33),
        )
    }

    @Test
    fun `storage is a runtime permission below R`() {
        val expected = GrantMechanism.Runtime(
            names = listOf(
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE",
            ),
        )
        assertEquals(expected, mechanism(Permission.STORAGE, 26))
        assertEquals(expected, mechanism(Permission.STORAGE, 29))
    }

    @Test
    fun `storage is all-files access from R on`() {
        assertEquals(
            GrantMechanism.AppOp("MANAGE_EXTERNAL_STORAGE"),
            mechanism(Permission.STORAGE, 30),
        )
        assertEquals(
            GrantMechanism.AppOp("MANAGE_EXTERNAL_STORAGE"),
            mechanism(Permission.STORAGE, 34),
        )
    }

    @Test
    fun `overlay and accessibility do not change with api`() {
        for (api in listOf(26, 30, 34)) {
            assertEquals(GrantMechanism.AppOp("SYSTEM_ALERT_WINDOW"), mechanism(Permission.OVERLAY, api))
            assertEquals(GrantMechanism.Accessibility, mechanism(Permission.ACCESSIBILITY, api))
        }
    }

    @Test
    fun `capability permissions have no version spec`() {
        assertNull(mechanism(Permission.ROOT, 34))
        assertNull(mechanism(Permission.SHIZUKU, 34))
    }
}
