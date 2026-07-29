package com.ziyou.ime.config

import android.content.Context

/**
 * 全键盘（QWERTY）输入方案偏好持久化。
 *
 * 语义：设置页「全键盘方案」选择的是**全键盘布局下使用的方案**（如朙月拼音、仓颉），
 * 写入本偏好而非直接打引擎；ZiYouInputMethodService.applyEngineForKeyboard 在
 * QWERTY 布局同步时读取并对齐引擎方案。九宫格等布局的专用方案
 * （见 [com.ziyou.ime.ime.KeyboardType.forcedSchemaId]）不经本偏好。
 *
 * 采用持久化偏好替代此前 Service 内存中的 schemeBeforeT9 记忆变量：
 * IME 进程重建后用户选择不丢失，且「布局 ↔ 方案」映射结果恒定可预测。
 */
object SchemaPreference {

    private const val PREF_NAME = "ziyou_schema"
    private const val KEY_QWERTY_SCHEMA = "qwerty_schema"

    /** 无偏好时的默认全键盘方案（与 default.yaml schema_list 首位一致） */
    const val DEFAULT_SCHEMA_ID = "luna_pinyin"

    /** 读取全键盘方案 id（未设置时回退 [DEFAULT_SCHEMA_ID]） */
    fun getQwertySchema(context: Context): String =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_QWERTY_SCHEMA, null) ?: DEFAULT_SCHEMA_ID

    /** 保存全键盘方案 id */
    fun setQwertySchema(context: Context, schemaId: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_QWERTY_SCHEMA, schemaId)
            .apply()
    }
}
