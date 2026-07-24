package com.ziyou.ime.config

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.util.Log
import com.ziyou.ime.core.level.LevelEngine
import com.ziyou.ime.level.LevelRepository

/**
 * 主题管理器
 * 管理键盘的视觉主题，提供3个预设主题：Light、Dark、Material
 * 通过SharedPreferences持久化用户的主题选择
 */
object ThemeManager {
    private const val TAG = "ThemeManager"

    // SharedPreferences键名
    private const val PREF_NAME = "ziyou_theme"
    private const val KEY_CURRENT_THEME = "current_theme"

    // 主题名称常量
    const val THEME_LIGHT = "Light"
    const val THEME_DARK = "Dark"
    const val THEME_MATERIAL = "Material"

    // 默认主题
    private const val DEFAULT_THEME = THEME_LIGHT

    // ===== 预设主题定义 =====

    private val lightTheme = KeyboardTheme(
        name = THEME_LIGHT,
        keyboardBackground = Color.parseColor("#F5F5F5"),
        keyBackground = Color.parseColor("#FFFFFF"),
        keyTextColor = Color.parseColor("#212121"),
        keyPressedBackground = Color.parseColor("#E0E0E0"),
        candidateBackground = Color.parseColor("#FFFFFF"),
        candidateTextColor = Color.parseColor("#212121"),
        candidateHighlightColor = Color.parseColor("#1976D2"),
        preeditTextColor = Color.parseColor("#424242"),
        borderColor = Color.parseColor("#BDBDBD")
    )

    private val darkTheme = KeyboardTheme(
        name = THEME_DARK,
        keyboardBackground = Color.parseColor("#303030"),
        keyBackground = Color.parseColor("#424242"),
        keyTextColor = Color.parseColor("#EEEEEE"),
        keyPressedBackground = Color.parseColor("#616161"),
        candidateBackground = Color.parseColor("#212121"),
        candidateTextColor = Color.parseColor("#E0E0E0"),
        candidateHighlightColor = Color.parseColor("#64B5F6"),
        preeditTextColor = Color.parseColor("#BDBDBD"),
        borderColor = Color.parseColor("#555555")
    )

    private val materialTheme = KeyboardTheme(
        name = THEME_MATERIAL,
        keyboardBackground = Color.parseColor("#E3F2FD"),
        keyBackground = Color.parseColor("#FFFFFF"),
        keyTextColor = Color.parseColor("#1565C0"),
        keyPressedBackground = Color.parseColor("#BBDEFB"),
        candidateBackground = Color.parseColor("#1976D2"),
        candidateTextColor = Color.parseColor("#FFFFFF"),
        candidateHighlightColor = Color.parseColor("#FFC107"),
        preeditTextColor = Color.parseColor("#0D47A1"),
        borderColor = Color.parseColor("#90CAF9")
    )

    private val themeMap = mapOf(
        THEME_LIGHT to lightTheme,
        THEME_DARK to darkTheme,
        THEME_MATERIAL to materialTheme
    )

    // ===== 公开API =====

    fun getCurrentTheme(context: Context): KeyboardTheme {
        val themeName = getPreferences(context).getString(KEY_CURRENT_THEME, DEFAULT_THEME)
        return themeMap[themeName] ?: lightTheme
    }

    fun getCurrentThemeName(context: Context): String {
        return getPreferences(context).getString(KEY_CURRENT_THEME, DEFAULT_THEME) ?: DEFAULT_THEME
    }

    fun setTheme(context: Context, themeName: String): Boolean {
        if (!themeMap.containsKey(themeName)) {
            Log.w(TAG, "未知的主题名称: $themeName")
            return false
        }
        // 皮肤需达到对应等级才能应用（权益解锁）
        if (!isThemeUnlocked(context, themeName)) {
            Log.w(TAG, "主题未解锁（等级不足）: $themeName")
            return false
        }
        getPreferences(context).edit()
            .putString(KEY_CURRENT_THEME, themeName)
            .apply()
        Log.i(TAG, "主题已切换为: $themeName")
        return true
    }

    /** 指定主题在当前等级下是否已解锁。 */
    fun isThemeUnlocked(context: Context, themeName: String): Boolean {
        val level = LevelRepository.load(context).level
        return LevelEngine.isThemeUnlocked(themeName, level)
    }

    /** 当前等级下已解锁的主题名称集合（供设置页筛选展示）。 */
    fun getUnlockedThemeNames(context: Context): List<String> {
        val level = LevelRepository.load(context).level
        return themeMap.keys.filter { LevelEngine.isThemeUnlocked(it, level) }
    }

    fun getAllThemes(): List<KeyboardTheme> {
        return themeMap.values.toList()
    }

    fun getAllThemeNames(): List<String> {
        return themeMap.keys.toList()
    }

    fun getThemeByName(name: String): KeyboardTheme? {
        return themeMap[name]
    }

    // ===== 内部方法 =====

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
}

/**
 * 键盘主题数据模型
 */
data class KeyboardTheme(
    val name: String,
    val keyboardBackground: Int,
    val keyBackground: Int,
    val keyTextColor: Int,
    val keyPressedBackground: Int,
    val candidateBackground: Int,
    val candidateTextColor: Int,
    val candidateHighlightColor: Int,
    val preeditTextColor: Int,
    val borderColor: Int
)
