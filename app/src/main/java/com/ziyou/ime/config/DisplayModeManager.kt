package com.ziyou.ime.config

import android.content.Context
import android.content.SharedPreferences
import com.ziyou.ime.core.floating.PanelPoint

/**
 * 键盘显示形态管理器
 *
 * 管理悬浮键盘相关的用户偏好（与 KeyboardType 正交的 DisplayMode 维度）：
 * - 悬浮模式总开关（手动切换，跨会话记忆）
 * - 横屏自动悬浮开关（进入横屏编辑时自动切悬浮，游戏场景主入口）
 * - 悬浮面板位置（横/竖屏各持久化一份，防转屏后面板丢失在屏幕外）
 * - 悬浮缩放因子（当前固定档位，预留设置项扩展）
 *
 * 仅存储几何与开关信息，无任何输入内容，符合项目隐私基线。
 */
object DisplayModeManager {

    private const val PREF_NAME = "ziyou_display_mode"

    private const val KEY_FLOATING_ENABLED = "floating_enabled"
    private const val KEY_AUTO_FLOAT_LANDSCAPE = "auto_float_landscape"
    private const val KEY_PANEL_X_PORTRAIT = "panel_x_port"
    private const val KEY_PANEL_Y_PORTRAIT = "panel_y_port"
    private const val KEY_PANEL_X_LANDSCAPE = "panel_x_land"
    private const val KEY_PANEL_Y_LANDSCAPE = "panel_y_land"

    /** 悬浮模式下键盘/候选/编码区的统一缩放因子（首版固定档位） */
    const val FLOATING_SCALE = 0.75f

    /** 悬浮面板宽度 = 容器宽度 × 该比例（再经最小/最大 dp 钳制） */
    const val PANEL_WIDTH_RATIO = 0.55f
    /** 面板最小宽度（dp） */
    const val PANEL_MIN_WIDTH_DP = 240
    /** 面板最大宽度（dp） */
    const val PANEL_MAX_WIDTH_DP = 340
    /** 默认位置的边缘内缩（dp） */
    const val PANEL_EDGE_MARGIN_DP = 16

    // ===== 悬浮模式开关 =====

    /** 悬浮模式是否开启（用户手动切换后的持久状态） */
    fun isFloatingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FLOATING_ENABLED, false)

    fun setFloatingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FLOATING_ENABLED, enabled).apply()
    }

    /** 横屏下是否自动进入悬浮模式（默认关，避免横屏视频等场景与用户预期冲突） */
    fun isAutoFloatInLandscape(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_FLOAT_LANDSCAPE, false)

    fun setAutoFloatInLandscape(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_FLOAT_LANDSCAPE, enabled).apply()
    }

    // ===== 面板位置（横/竖屏各一份） =====

    /** 读取持久化的面板位置；从未保存过时返回 null（由容器使用默认右下角位置） */
    fun loadPanelPosition(context: Context, landscape: Boolean): PanelPoint? {
        val p = prefs(context)
        val keyX = if (landscape) KEY_PANEL_X_LANDSCAPE else KEY_PANEL_X_PORTRAIT
        val keyY = if (landscape) KEY_PANEL_Y_LANDSCAPE else KEY_PANEL_Y_PORTRAIT
        if (!p.contains(keyX) || !p.contains(keyY)) return null
        return PanelPoint(p.getInt(keyX, 0), p.getInt(keyY, 0))
    }

    fun savePanelPosition(context: Context, landscape: Boolean, position: PanelPoint) {
        val keyX = if (landscape) KEY_PANEL_X_LANDSCAPE else KEY_PANEL_X_PORTRAIT
        val keyY = if (landscape) KEY_PANEL_Y_LANDSCAPE else KEY_PANEL_Y_PORTRAIT
        prefs(context).edit()
            .putInt(keyX, position.x)
            .putInt(keyY, position.y)
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
