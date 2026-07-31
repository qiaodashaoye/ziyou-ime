package com.ziyou.ime.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ziyou.ime.ime.KeyboardType

/**
 * 用户初始偏好仓库（首启偏好向导 UserPreferenceSetupActivity 的持久化层）。
 *
 * 与项目其他 Repository 保持一致：单例 object + SharedPreferences，
 * 对外暴露带 context 参数的静态方法。
 *
 * 各偏好项的生效路径：
 * - 键盘布局：写入 IME 服务同一份偏好文件（ziyou_keyboard/keyboard_type），
 *   ZiYouInputMethodService 重建输入视图时经 loadKeyboardType 读取生效；
 * - 候选词每页数量：当前仅持久化偏好记录（引擎 menu/page_size 的运行时写入
 *   通道尚未开通，接入后从此处读取），默认值与 assets/rime/default.yaml 一致；
 * - 中文联想：不在本仓库存储，直接复用 [AssociationManager]。
 */
object UserPreferenceRepository {

    private const val TAG = "UserPreferenceRepo"

    private const val PREF_NAME = "ziyou_user_preference"
    private const val KEY_SETUP_DONE = "initial_setup_done"
    private const val KEY_CANDIDATE_PAGE_SIZE = "candidate_page_size"

    // 键盘布局与 IME 服务共用同一份偏好：
    // 常量必须与 ZiYouInputMethodService 的 PREF_NAME / KEY_KEYBOARD_TYPE 保持一致
    private const val IME_PREF_NAME = "ziyou_keyboard"
    private const val IME_KEY_KEYBOARD_TYPE = "keyboard_type"

    /** 候选词每页数量可选值；默认 10 与 assets/rime/default.yaml 的 menu/page_size 一致 */
    val CANDIDATE_PAGE_SIZE_OPTIONS = listOf(5, 10, 20)
    const val DEFAULT_CANDIDATE_PAGE_SIZE = 10

    // ===== 首启向导完成标记 =====

    /** 初始偏好设置是否已完成（完成后首启向导不再展示） */
    fun isSetupDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SETUP_DONE, false)

    /** 标记初始偏好设置已完成 */
    fun markSetupDone(context: Context) {
        prefs(context).edit().putBoolean(KEY_SETUP_DONE, true).apply()
        Log.i(TAG, "初始偏好设置已完成")
    }

    // ===== 键盘布局 =====

    /** 当前键盘布局偏好（与 IME 服务读取的是同一份数据） */
    fun getKeyboardLayout(context: Context): KeyboardType {
        val name = context.getSharedPreferences(IME_PREF_NAME, Context.MODE_PRIVATE)
            .getString(IME_KEY_KEYBOARD_TYPE, null)
        return KeyboardType.fromName(name)
    }

    /** 保存键盘布局偏好；IME 下次创建输入视图时生效 */
    fun setKeyboardLayout(context: Context, type: KeyboardType) {
        context.getSharedPreferences(IME_PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(IME_KEY_KEYBOARD_TYPE, type.name)
            .apply()
        Log.i(TAG, "键盘布局偏好: ${type.name}")
    }

    // ===== 候选词每页数量 =====

    /** 候选词每页数量偏好 */
    fun getCandidatePageSize(context: Context): Int =
        prefs(context).getInt(KEY_CANDIDATE_PAGE_SIZE, DEFAULT_CANDIDATE_PAGE_SIZE)

    /** 保存候选词每页数量偏好（非法值回退默认，防御外部误传） */
    fun setCandidatePageSize(context: Context, size: Int) {
        val valid = if (size in CANDIDATE_PAGE_SIZE_OPTIONS) size else DEFAULT_CANDIDATE_PAGE_SIZE
        prefs(context).edit().putInt(KEY_CANDIDATE_PAGE_SIZE, valid).apply()
        Log.i(TAG, "候选词每页数量偏好: $valid")
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
