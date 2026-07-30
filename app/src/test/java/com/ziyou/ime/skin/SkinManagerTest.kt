package com.ziyou.ime.skin

import com.ziyou.ime.core.skin.SkinDefaults
import com.ziyou.ime.testing.FakeSkinContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [SkinManager] 快照缓存测试：命中复用、失效重建、非法切换拒绝、
 * 规格损坏回退默认皮肤。
 *
 * 纯 JVM 环境下 mainHandler 为存根（post 无操作），后台异步重建不会落地，
 * 快照仅由 [SkinManager.getCurrentSkin] 同步路径更新——测试因此天然确定。
 */
class SkinManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: FakeSkinContext

    @Before
    fun setUp() {
        context = FakeSkinContext(tempFolder.root)
        // 深浅色固定为浅色，避免存根 Resources 不可用（FOLLOW_SYSTEM 需读 uiMode）
        SkinRepository.setDarkModePolicy(context, SkinRepository.DarkModePolicy.FORCE_LIGHT)
        // SkinManager 为进程级单例：提升覆盖修订号使上个用例的快照必然失效
        SkinManager.invalidate(context)
    }

    @Test
    fun getCurrentSkin_cacheHitReturnsSameSnapshot() {
        val first = SkinManager.getCurrentSkin(context)
        val second = SkinManager.getCurrentSkin(context)
        assertSame(first, second)
        assertEquals(SkinDefaults.DEFAULT_SKIN_ID, first.id)
    }

    @Test
    fun invalidate_discardsSnapshot() {
        val before = SkinManager.getCurrentSkin(context)
        SkinManager.invalidate(context)
        val after = SkinManager.getCurrentSkin(context)
        assertNotSame(before, after)
        assertEquals(before.id, after.id)
    }

    @Test
    fun currentSkinIdChange_invalidatesSnapshot() {
        val light = SkinManager.getCurrentSkin(context)
        SkinRepository.setCurrentSkinId(context, SkinDefaults.ID_MATERIAL)
        val material = SkinManager.getCurrentSkin(context)
        assertNotSame(light, material)
        assertEquals(SkinDefaults.ID_MATERIAL, material.id)
    }

    @Test
    fun setSkin_unknownId_rejectedWithoutChange() {
        SkinManager.getCurrentSkin(context)
        val before = SkinRepository.getCurrentSkinId(context)
        assertFalse(SkinManager.setSkin(context, "com.test.nonexistent"))
        assertEquals(before, SkinRepository.getCurrentSkinId(context))
    }

    @Test
    fun corruptedCurrentSkin_fallsBackToDefault() {
        // 当前皮肤指向不存在的安装目录 → 轻量构建失败 → 回退默认并复位 id
        SkinRepository.setCurrentSkinId(context, "com.test.absent")
        val theme = SkinManager.getCurrentSkin(context)
        assertEquals(SkinDefaults.DEFAULT_SKIN_ID, theme.id)
        assertEquals(SkinDefaults.DEFAULT_SKIN_ID, SkinRepository.getCurrentSkinId(context))
    }
}
