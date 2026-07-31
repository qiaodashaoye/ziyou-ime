package com.ziyou.ime.core.level

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LevelEngine] 纯计算引擎回归测试。
 *
 * 覆盖：分段计分（全额/半额/封顶）、连续签到奖励、等级判定与进度、皮肤/音效解锁门槛。
 * 主题名使用字面量（与 SkinDefaults 内置皮肤 meta.name 一致），避免测试引入 Android 依赖。
 */
class LevelEngineTest {

    // ===== 分段计分 =====

    @Test
    fun scoreForChars_fullRate_firstTierOnePerChar() {
        assertEquals(100, LevelEngine.scoreForChars(0, 100))
        assertEquals(2000, LevelEngine.scoreForChars(0, 2000))
    }

    @Test
    fun scoreForChars_halfRate_secondTierHalfPerChar() {
        // 已达 2000 字后每字 0.5 分
        assertEquals(50, LevelEngine.scoreForChars(2000, 100))
        // 从 0 到 3000：2000*1 + 1000*0.5 = 2500
        assertEquals(2500, LevelEngine.scoreForChars(0, 3000))
    }

    @Test
    fun scoreForChars_beyondCap_noScore() {
        // 超过 6000 字不再计分
        assertEquals(0, LevelEngine.scoreForChars(6000, 100))
    }

    @Test
    fun scoreForChars_nonPositive_returnsZero() {
        assertEquals(0, LevelEngine.scoreForChars(100, 0))
        assertEquals(0, LevelEngine.scoreForChars(100, -5))
    }

    // ===== 连续签到奖励 =====

    @Test
    fun streakBonus_rules() {
        assertEquals(0, LevelEngine.streakBonus(1))
        assertEquals(5, LevelEngine.streakBonus(2))
        assertEquals(10, LevelEngine.streakBonus(3))
        // 单日 step 封顶 30；第 7 天额外 +50 → 30 + 50
        assertEquals(80, LevelEngine.streakBonus(7))
    }

    // ===== 等级判定 =====

    @Test
    fun levelForPoints_boundaries() {
        assertEquals(1, LevelEngine.levelForPoints(0))
        assertEquals(1, LevelEngine.levelForPoints(99))
        assertEquals(2, LevelEngine.levelForPoints(100))
        assertEquals(10, LevelEngine.levelForPoints(16000))
        assertEquals(10, LevelEngine.levelForPoints(999_999))
    }

    @Test
    fun levelName_returnsExpected() {
        assertEquals("初识键盘", LevelEngine.levelName(1))
        assertEquals("输入达人", LevelEngine.levelName(10))
    }

    @Test
    fun progressInLevel_midLevelAndMax() {
        // Lv.2 区间 [100,300)，200 分 → 50%
        assertEquals(0.5f, LevelEngine.progressInLevel(200), 0.0001f)
        // 满级恒为 1
        assertEquals(1f, LevelEngine.progressInLevel(16000), 0.0001f)
        assertEquals(0f, LevelEngine.progressInLevel(0), 0.0001f)
    }

    @Test
    fun thresholds_andNextLevel() {
        assertEquals(0L, LevelEngine.thresholdForLevel(1))
        assertEquals(16000L, LevelEngine.thresholdForLevel(10))
        assertEquals(100L, LevelEngine.nextLevelThreshold(1))
        // 满级下一级门槛回退为当前级门槛
        assertEquals(16000L, LevelEngine.nextLevelThreshold(10))
    }

    // ===== 权益解锁 =====

    @Test
    fun themeUnlock_byLevel() {
        // 悬浮立体为默认皮肤，Lv.1 起可用
        assertTrue(LevelEngine.isThemeUnlocked("悬浮立体", 1))
        assertEquals(1, LevelEngine.themeUnlockLevel("悬浮立体"))
        assertFalse(LevelEngine.isThemeUnlocked("云雾拟态", 1))
        assertTrue(LevelEngine.isThemeUnlocked("云雾拟态", 2))
        assertFalse(LevelEngine.isThemeUnlocked("Material", 6))
        assertFalse(LevelEngine.isThemeUnlocked("Material", 1))
        assertTrue(LevelEngine.isThemeUnlocked("Material", 7))
    }

    @Test
    fun soundPackUnlock_byLevel() {
        assertTrue(LevelEngine.isSoundPackUnlocked("default", 1))
        assertFalse(LevelEngine.isSoundPackUnlocked("basic", 1))
        assertTrue(LevelEngine.isSoundPackUnlocked("basic", 5))
    }
}
