package com.ziyou.ime.ai.prediction

import android.content.Context

/**
 * LLM 智能续写功能开关配置。
 *
 * 隐私合规（见 docs/智能预测可行性方案.md §4.6）：独立开关**默认关**，
 * 与 AI 面板开关分离；端点/Key/模型不新增配置项，直接复用
 * [com.ziyou.ime.ai.AiConfig]（用户在 AI 设置中配置的服务）。
 * 启用状态独立持久化于专属 SharedPreferences 文件。
 */
object LlmPredictionConfig {

    private const val PREF_NAME = "ziyou_llm_prediction"

    /** 功能总开关 key（默认 false：数据外发功能必须经用户明示同意） */
    const val KEY_ENABLED = "llm_prediction_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
