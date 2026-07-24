package com.ziyou.ime.core.level

import kotlin.math.floor
import kotlin.math.min

/**
 * 等级体系纯计算引擎（无状态、无 I/O）。
 *
 * 职责：等级曲线、上屏字符→积分的分段计分、连续天数奖励、等级→权益（皮肤/音效）解锁规则。
 * 所有函数均为纯函数，便于单元测试；持久化由 [LevelRepository] 负责，热路径计数由 [LevelStats] 负责。
 *
 * 详见《等级体系可行性方案》第 3、4 节。
 */
object LevelEngine {

    /** MVP 阶段的等级上限（1–10 级）。 */
    const val MAX_LEVEL = 10

    // 主题名（与 com.ziyou.ime.config.ThemeManager 的常量保持一致）。
    // 此处以字面量声明，避免纯逻辑模块反向依赖 Android 配置层（ThemeManager）。
    private const val THEME_LIGHT = "Light"
    private const val THEME_DARK = "Dark"
    private const val THEME_MATERIAL = "Material"

    /**
     * 1–10 级累计积分门槛（指数型递增，前快后慢）。
     * 索引 = 等级-1，即 LEVEL_THRESHOLDS[0] 为 Lv.1 起点。
     */
    private val LEVEL_THRESHOLDS = longArrayOf(
        0L,      // Lv.1 初识键盘
        100L,    // Lv.2 键客入门
        300L,    // Lv.3 指尖舞者
        700L,    // Lv.4 码字学徒
        1400L,   // Lv.5 连击高手
        2500L,   // Lv.6 效率专员
        4200L,   // Lv.7 键盘侠客
        6800L,   // Lv.8 文字猎手
        10500L,  // Lv.9 灵感速记
        16000L   // Lv.10 输入达人
    )

    /** 各等级名称，索引 = 等级-1。 */
    private val LEVEL_NAMES = arrayOf(
        "初识键盘", "键客入门", "指尖舞者", "码字学徒", "连击高手",
        "效率专员", "键盘侠客", "文字猎手", "灵感速记", "输入达人"
    )

    // ===== 分段计分（上屏字符数 → 积分）=====

    /** 全额计分区间上界：当日前 2000 字，每字 1 分。 */
    private const val FULL_RATE_CHARS = 2000

    /** 半额计分区间上界：2000–6000 字，每字 0.5 分；超出 6000 字不再计分。 */
    private const val HALF_RATE_CHARS = 6000

    /** 当日累计 [chars] 个上屏字符对应的累计积分（分段递减）。 */
    private fun cumulativePoints(chars: Int): Double {
        val safe = chars.coerceAtLeast(0)
        val tier1 = min(safe, FULL_RATE_CHARS)
        val tier2 = (min(safe, HALF_RATE_CHARS) - FULL_RATE_CHARS).coerceAtLeast(0)
        return tier1 * 1.0 + tier2 * 0.5
    }

    /**
     * 计算本次新增 [addChars] 个上屏字符能获得的积分。
     * @param charsBeforeToday 当日在此之前已累计的上屏字符数（用于确定所处计分档位）
     * @return 本次获得的积分（向下取整，只增不减）
     */
    fun scoreForChars(charsBeforeToday: Int, addChars: Int): Int {
        if (addChars <= 0) return 0
        val before = cumulativePoints(charsBeforeToday)
        val after = cumulativePoints(charsBeforeToday + addChars)
        return floor(after - before).toInt().coerceAtLeast(0)
    }

    // ===== 连续天数奖励 =====

    /** 每日首次使用固定奖励。 */
    const val DAILY_FIRST_USE_BONUS = 10

    /**
     * 连续使用第 [streakDays] 天的额外奖励（不含每日首用固定分）。
     * 规则：第 2 天 +5，逐日 +5，单日封顶 +30；每满 7 天额外 +50。
     */
    fun streakBonus(streakDays: Int): Int {
        if (streakDays <= 1) return 0
        val step = ((streakDays - 1) * 5).coerceAtMost(30)
        val cycle = if (streakDays % 7 == 0) 50 else 0
        return step + cycle
    }

    // ===== 等级判定 =====

    /** 根据累计积分判定当前等级（1..MAX_LEVEL）。 */
    fun levelForPoints(totalPoints: Long): Int {
        var level = 1
        for (i in LEVEL_THRESHOLDS.indices) {
            if (totalPoints >= LEVEL_THRESHOLDS[i]) level = i + 1 else break
        }
        return level
    }

    /** 等级名称。 */
    fun levelName(level: Int): String {
        val idx = (level - 1).coerceIn(0, LEVEL_NAMES.lastIndex)
        return LEVEL_NAMES[idx]
    }

    /** 达到某等级所需的累计积分门槛。 */
    fun thresholdForLevel(level: Int): Long {
        val idx = (level - 1).coerceIn(0, LEVEL_THRESHOLDS.lastIndex)
        return LEVEL_THRESHOLDS[idx]
    }

    /**
     * 下一级所需累计积分门槛；已满级时返回当前级门槛（进度视为满）。
     */
    fun nextLevelThreshold(level: Int): Long {
        if (level >= MAX_LEVEL) return thresholdForLevel(MAX_LEVEL)
        return thresholdForLevel(level + 1)
    }

    /**
     * 当前等级内进度 [0f, 1f]。满级恒为 1f。
     * 例如 Lv.7 位于 4200，下一级 6800，则 (points-4200)/(6800-4200)。
     */
    fun progressInLevel(totalPoints: Long): Float {
        val level = levelForPoints(totalPoints)
        if (level >= MAX_LEVEL) return 1f
        val cur = thresholdForLevel(level)
        val next = nextLevelThreshold(level)
        if (next <= cur) return 1f
        return ((totalPoints - cur).toFloat() / (next - cur).toFloat()).coerceIn(0f, 1f)
    }

    // ===== 权益解锁：皮肤 / 音效 =====

    /**
     * 皮肤（主题）解锁所需等级。
     * Light 为默认皮肤（Lv.1 起可用）；Dark 于 Lv.2 解锁；Material 于 Lv.7 解锁。
     */
    private val THEME_UNLOCK_LEVEL: Map<String, Int> = mapOf(
        THEME_LIGHT to 1,
        THEME_DARK to 2,
        THEME_MATERIAL to 7
    )

    /**
     * 音效包解锁所需等级（面向后续音效系统预留；键名为音效包标识）。
     * Lv.5 解锁「基础音效包」。
     */
    private val SOUND_PACK_UNLOCK_LEVEL: Map<String, Int> = mapOf(
        SOUND_PACK_DEFAULT to 1,
        SOUND_PACK_BASIC to 5
    )

    /** 指定皮肤在 [level] 等级下是否已解锁（未在表中的皮肤默认视为 Lv.1 解锁）。 */
    fun isThemeUnlocked(themeName: String, level: Int): Boolean {
        val required = THEME_UNLOCK_LEVEL[themeName] ?: 1
        return level >= required
    }

    /** 当前等级下已解锁的皮肤名称集合。 */
    fun unlockedThemes(level: Int): List<String> =
        THEME_UNLOCK_LEVEL.filterValues { level >= it }.keys.toList()

    /** 指定音效包在 [level] 等级下是否已解锁。 */
    fun isSoundPackUnlocked(packId: String, level: Int): Boolean {
        val required = SOUND_PACK_UNLOCK_LEVEL[packId] ?: 1
        return level >= required
    }

    /** 当前等级下已解锁的音效包集合。 */
    fun unlockedSoundPacks(level: Int): List<String> =
        SOUND_PACK_UNLOCK_LEVEL.filterValues { level >= it }.keys.toList()
}

/** 默认音效包标识（始终可用）。 */
const val SOUND_PACK_DEFAULT = "default"

/** 基础音效包标识（Lv.5 解锁）。 */
const val SOUND_PACK_BASIC = "basic"
