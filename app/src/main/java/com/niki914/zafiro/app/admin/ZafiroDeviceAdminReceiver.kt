package com.niki914.zafiro.app.admin

import android.app.admin.DeviceAdminReceiver

/** Makes Zafiro a device administrator (lock-screen policy only). Off until the owner enables it in Settings. */
class ZafiroDeviceAdminReceiver : DeviceAdminReceiver()
