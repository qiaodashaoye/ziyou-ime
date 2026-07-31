package com.ziyou.ime.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * 换行键语义解析（纯逻辑，无副作用）。
 *
 * Rime 只在存在编码时消费 Return（`Editor::ProcessKeyEvent` 仅对 `IsComposing()` 生效），
 * 无编码时按键落到 IME 自身，需按当前编辑器的 [EditorInfo] 决定语义：
 * - 多行输入框 / 声明 `IME_FLAG_NO_ENTER_ACTION` / 无具体动作 → [ACTION_NEWLINE]（插入换行符）
 * - 声明了搜索、发送、前往等动作 → 返回该 actionId，交
 *   [android.view.inputmethod.InputConnection.performEditorAction] 执行
 *
 * 与 [EditorImageSupport] 同源思路：Android 侧的编辑器能力判定集中在纯函数中，便于单测。
 */
object EnterKeyBehavior {

    /** 无编辑器动作：换行键应插入换行符（取负值，与任何合法 actionId 不冲突） */
    const val ACTION_NEWLINE = -1

    /** 换行键默认键面文字（键面以此文字标注的按键参与动作文案替换） */
    const val LABEL_NEWLINE = "换行"

    /** 解析当前编辑器下换行键应执行的动作；[editorInfo] 为空按换行处理。 */
    fun actionOf(editorInfo: EditorInfo?): Int =
        resolveAction(editorInfo?.imeOptions ?: 0, editorInfo?.inputType ?: 0)

    /** 换行键键面文字：有具体编辑器动作时显示动作名（搜索 / 发送…），否则「换行」。 */
    fun labelOf(editorInfo: EditorInfo?): String = labelForAction(actionOf(editorInfo))

    /**
     * 解析换行键动作。
     *
     * 多行输入框优先按换行处理：多行编辑器即使声明了 action，用户按回车的预期也是换行
     * （与系统输入法一致）。
     */
    fun resolveAction(imeOptions: Int, inputType: Int): Int {
        if (isMultiLine(inputType)) return ACTION_NEWLINE
        if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return ACTION_NEWLINE
        return when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_PREVIOUS -> action
            // IME_ACTION_UNSPECIFIED / IME_ACTION_NONE：由输入法自行决定，按换行处理
            else -> ACTION_NEWLINE
        }
    }

    /** 动作对应的键面文字 */
    fun labelForAction(action: Int): String = when (action) {
        EditorInfo.IME_ACTION_GO -> "前往"
        EditorInfo.IME_ACTION_SEARCH -> "搜索"
        EditorInfo.IME_ACTION_SEND -> "发送"
        EditorInfo.IME_ACTION_NEXT -> "下一项"
        EditorInfo.IME_ACTION_DONE -> "完成"
        EditorInfo.IME_ACTION_PREVIOUS -> "上一项"
        else -> LABEL_NEWLINE
    }

    /** 是否多行文本输入框（显式多行或允许 IME 多行） */
    private fun isMultiLine(inputType: Int): Boolean =
        inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0 ||
                inputType and InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE != 0)
}
