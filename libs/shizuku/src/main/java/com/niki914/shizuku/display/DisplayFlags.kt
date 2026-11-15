package com.niki914.shizuku.display

import android.os.Build

/**
 * 虚拟屏创建旗标计算器。
 */
object DisplayFlags {
    fun compute(sdk: Int, trusted: Boolean): Int {
        val flagPublic = 1 shl 0
        val flagOwnContentOnly = 1 shl 3
        val flagSupportsTouch = 1 shl 6
        val flagRotatesWithContent = 1 shl 7
        val flagDestroyContentOnRemoval = 1 shl 8
        val flagTrusted = 1 shl 10
        val flagOwnDisplayGroup = 1 shl 11
        val flagAlwaysUnlocked = 1 shl 12
        val flagTouchFeedbackDisabled = 1 shl 13
        val flagOwnFocus = 1 shl 14
        val flagStealTopFocusDisabled = 1 shl 16

        var flags = flagPublic or flagOwnContentOnly or flagSupportsTouch or
            flagRotatesWithContent or flagDestroyContentOnRemoval
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or flagTouchFeedbackDisabled
            if (trusted) {
                flags = flags or flagTrusted or flagOwnDisplayGroup or flagAlwaysUnlocked
            }
        }
        if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && trusted) {
            flags = flags or flagOwnFocus or flagStealTopFocusDisabled
        }
        return flags
    }
}
