package com.ziyou.ime.level

import android.content.Context
import android.content.SharedPreferences
import com.ziyou.ime.core.level.LevelEngine
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 等级系统持久化状态（单一数据源）。
 *
 * 全部为脱敏聚合计数，绝不含任何输入内容。详见《等级体系可行性方案》第 6.3、7.1 节。
 */
data class LevelState(
    /** 累计积分（只增不减）。 */
    val totalPoints: Long = 0,
    /** 当前等级。 */
    val level: Int = 1,
    /** 当日日期 yyyy-MM-dd，用于每日重置。 */
    val todayDate: String = "",
    /** 当日已上屏字符数（用于分段计分档位判定）。 */
    val todayChars: Int = 0,
    /** 当日已获得积分（用于每日简报）。 */
    val todayPoints: Int = 0,
    /** 连续使用天数。 */
    val streakDays: Int = 0,
    /** 上次活跃日期 yyyy-MM-dd。 */
    val lastActiveDate: String = ""
)

/** 每日首次使用签到结果，用于「每日简报」UI 展示。 */
data class CheckInResult(
    val state: LevelState,
    /** 本次是否为当日首次使用（true 才展示简报/发放首用奖励）。 */
    val isFirstUseToday: Boolean,
    /** 本次首用发放的积分（首用固定分 + 连续天数奖励）。 */
    val bonusPoints: Int,
    /** 昨日上屏字符数（简报展示，来自结算前的旧状态）。 */
    val yesterdayChars: Int,
    /** 昨日获得积分（简报展示）。 */
    val yesterdayPoints: Int
)

/**
 * 等级状态持久化仓库。
 *
 * 沿用项目现有惯例（无 Room，[SharedPreferences] 持久化，参见 [com.ziyou.ime.data.SideSymbolRepository]）。
 * 因 IME 服务与设置页同属默认主进程（无 android:process 声明），共享同一份 prefs、数据一致。
 *
 * 所有写方法 [Synchronized]，保证热路径异步落盘与设置页读取之间的一致性。
 */
object LevelRepository {

    private const val PREF_NAME = "ziyou_level"

    private const val KEY_TOTAL_POINTS = "total_points"
    private const val KEY_LEVEL = "level"
    private const val KEY_TODAY_DATE = "today_date"
    private const val KEY_TODAY_CHARS = "today_chars"
    private const val KEY_TODAY_POINTS = "today_points"
    private const val KEY_STREAK_DAYS = "streak_days"
    private const val KEY_LAST_ACTIVE_DATE = "last_active_date"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** 读取当前等级状态。 */
    fun load(context: Context): LevelState {
        val p = prefs(context)
        return LevelState(
            totalPoints = p.getLong(KEY_TOTAL_POINTS, 0),
            level = p.getInt(KEY_LEVEL, 1),
            todayDate = p.getString(KEY_TODAY_DATE, "") ?: "",
            todayChars = p.getInt(KEY_TODAY_CHARS, 0),
            todayPoints = p.getInt(KEY_TODAY_POINTS, 0),
            streakDays = p.getInt(KEY_STREAK_DAYS, 0),
            lastActiveDate = p.getString(KEY_LAST_ACTIVE_DATE, "") ?: ""
        )
    }

    /**
     * 累计本次批量上屏的字符数并结算积分（由 [LevelStats] 防抖后在后台线程调用）。
     *
     * @param chars 自上次落盘以来累计的上屏字符数（>0）
     * @return 结算后的最新状态
     */
    @Synchronized
    fun accumulate(context: Context, chars: Int): LevelState {
        if (chars <= 0) return load(context)
        val today = today()
        var s = rollover(load(context), today)

        val gained = LevelEngine.scoreForChars(s.todayChars, chars)
        s = s.copy(
            totalPoints = s.totalPoints + gained,
            todayChars = s.todayChars + chars,
            todayPoints = s.todayPoints + gained
        )
        s = s.copy(level = LevelEngine.levelForPoints(s.totalPoints))
        save(context, s)
        return s
    }

    /**
     * 标记当日活跃：发放每日首用与连续天数奖励（每日仅一次，幂等）。
     * 建议在 [android.inputmethodservice.InputMethodService.onStartInputView] 时调用。
     */
    @Synchronized
    fun checkInToday(context: Context): CheckInResult {
        val old = load(context)
        val today = today()

        // 已在今日签到过：直接返回，不重复发放
        if (old.lastActiveDate == today) {
            val rolled = rollover(old, today)
            if (rolled !== old) save(context, rolled)
            return CheckInResult(rolled, isFirstUseToday = false, bonusPoints = 0,
                yesterdayChars = 0, yesterdayPoints = 0)
        }

        // 记录昨日战报（结算前的当日数据）供简报展示
        val yChars = old.todayChars
        val yPoints = old.todayPoints

        val newStreak = if (old.lastActiveDate == yesterdayOf(today)) old.streakDays + 1 else 1
        val bonus = LevelEngine.DAILY_FIRST_USE_BONUS + LevelEngine.streakBonus(newStreak)

        // 跨日：重置当日计数，并计入首用奖励
        var s = old.copy(
            todayDate = today,
            todayChars = 0,
            todayPoints = bonus,
            streakDays = newStreak,
            lastActiveDate = today,
            totalPoints = old.totalPoints + bonus
        )
        s = s.copy(level = LevelEngine.levelForPoints(s.totalPoints))
        save(context, s)
        return CheckInResult(s, isFirstUseToday = true, bonusPoints = bonus,
            yesterdayChars = yChars, yesterdayPoints = yPoints)
    }

    /** 测试/重置用途：清空全部等级数据。 */
    @Synchronized
    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
    }

    // ===== 内部实现 =====

    /** 若 [state] 的当日日期不是 [today]，重置当日计数（保留累计分与等级）。 */
    private fun rollover(state: LevelState, today: String): LevelState {
        if (state.todayDate == today) return state
        return state.copy(todayDate = today, todayChars = 0, todayPoints = 0)
    }

    private fun save(context: Context, s: LevelState) {
        prefs(context).edit()
            .putLong(KEY_TOTAL_POINTS, s.totalPoints)
            .putInt(KEY_LEVEL, s.level)
            .putString(KEY_TODAY_DATE, s.todayDate)
            .putInt(KEY_TODAY_CHARS, s.todayChars)
            .putInt(KEY_TODAY_POINTS, s.todayPoints)
            .putInt(KEY_STREAK_DAYS, s.streakDays)
            .putString(KEY_LAST_ACTIVE_DATE, s.lastActiveDate)
            .apply()
    }

    private fun today(): String = dateFormat.format(Calendar.getInstance().time)

    private fun yesterdayOf(today: String): String {
        val cal = Calendar.getInstance()
        cal.time = dateFormat.parse(today) ?: return ""
        cal.add(Calendar.DAY_OF_YEAR, -1)
        return dateFormat.format(cal.time)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
