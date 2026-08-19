package com.ziyou.ime.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ImageSupportPolicy] 图片能力裁决回归测试。
 *
 * 覆盖：MIME 动态检测优先、白名单兜底、未知/空包名回退保存、
 * 白名单包名匹配。包名使用字面量（与枚举定义一致），无 Android 依赖。
 */
class ImageSupportPolicyTest {

    // ===== 动态检测（权威信号）=====

    @Test
    fun declaredImageMime_alwaysSend() {
        // 声明了 image/* 的编辑器无论包名一律可发送
        assertEquals(ImageSupportLevel.SEND, ImageSupportPolicy.resolve(true, "com.unknown.app"))
        assertEquals(ImageSupportLevel.SEND, ImageSupportPolicy.resolve(true, null))
    }

    // ===== 白名单兜底 =====

    @Test
    fun whitelistPackage_withoutMime_send() {
        assertEquals(ImageSupportLevel.SEND, ImageSupportPolicy.resolve(false, "com.tencent.mm"))
        assertEquals(ImageSupportLevel.SEND, ImageSupportPolicy.resolve(false, "org.telegram.messenger"))
    }

    @Test
    fun unknownPackage_withoutMime_saveOnly() {
        assertEquals(ImageSupportLevel.SAVE_ONLY, ImageSupportPolicy.resolve(false, "com.unknown.app"))
    }

    @Test
    fun nullPackage_withoutMime_saveOnly() {
        assertEquals(ImageSupportLevel.SAVE_ONLY, ImageSupportPolicy.resolve(false, null))
    }

    // ===== 白名单枚举 =====

    @Test
    fun imageCapableApp_containsExactPackageOnly() {
        assertTrue(ImageCapableApp.contains("com.tencent.mobileqq"))
        assertFalse(ImageCapableApp.contains("com.tencent.mobileqq.fake"))
        assertFalse(ImageCapableApp.contains(""))
        assertFalse(ImageCapableApp.contains(null))
    }

    @Test
    fun imageCapableApp_entriesAllRegistered() {
        // 每个枚举条目的包名都必须能被 contains 命中（防止扩展时集合遗漏）
        for (app in ImageCapableApp.entries) {
            assertTrue(app.packageName, ImageCapableApp.contains(app.packageName))
        }
    }
}
