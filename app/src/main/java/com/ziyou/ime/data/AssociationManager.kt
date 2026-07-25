package com.ziyou.ime.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 联想功能开关（引擎级联想 librime-predict 的应用侧控制）。
 *
 * 应用层联想管线（AssociationPipeline / UserBigramModel 等）已按简洁性决策整体移除
 * （演进记录见 docs/联想功能重构方案.md 第 11 节），联想能力完全由 librime-predict
 * 插件提供：启用后引擎在 commit 后把预测词写入 context.menu，走既有候选渲染与
 * Rime 选词路径，无需应用层数据源。
 *
 * 本类仅负责持久化联想开关，并由 ZiYouInputMethodService.applyEngineForKeyboard
 * 把开关映射为 Rime 运行时选项 `prediction`（predictor 源码中的门控选项名）。
 *
 * 注意：当前预编译 librime.a 尚未编入 predict 模块（WITH_PREDICT=OFF），
 * 开关在启用前为无害 no-op；启用步骤见 librime-prebuilt/README.md 第 5 节。
 */
object AssociationManager {

    private const val TAG = "AssociationManager"

    private const val PREF_NAME = "ziyou_association"
    private const val KEY_ENABLED = "association_enabled"

    /** 默认开启（与 yuyan `chinese_association_enable` 默认值一致） */
    private const val DEFAULT_ENABLED = true

    /** 联想功能是否启用 */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    /** 设置联想功能开关（下次引擎状态同步时生效） */
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        Log.i(TAG, "联想功能开关: $enabled")
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
