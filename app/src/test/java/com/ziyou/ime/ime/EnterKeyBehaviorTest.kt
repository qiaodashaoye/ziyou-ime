package com.ziyou.ime.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [EnterKeyBehavior] 换行键语义解析测试。
 *
 * 覆盖「插入换行 vs 执行编辑器动作」的判定与键面文案，
 * 与 [InputLogicController] 回车键落地路径同源。
 */
class EnterKeyBehaviorTest {

    @Test
    fun resolveAction_noImeOptions_isNewline() {
        assertEquals(
            EnterKeyBehavior.ACTION_NEWLINE,
            EnterKeyBehavior.resolveAction(0, 0)
        )
    }

    @Test
    fun resolveAction_actionNone_isNewline() {
        assertEquals(
            EnterKeyBehavior.ACTION_NEWLINE,
            EnterKeyBehavior.resolveAction(EditorInfo.IME_ACTION_NONE, 0)
        )
    }

    @Test
    fun resolveAction_declaredActions_returnActionId() {
        val actions = listOf(
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_PREVIOUS
        )
        actions.forEach { action ->
            assertEquals(action, EnterKeyBehavior.resolveAction(action, 0))
        }
    }

    @Test
    fun resolveAction_actionWithExtraFlags_ignoresNonActionBits() {
        // imeOptions 常带 IME_FLAG_NO_FULLSCREEN 等标志位，动作需按掩码取
        val imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_FULLSCREEN
        assertEquals(
            EditorInfo.IME_ACTION_SEND,
            EnterKeyBehavior.resolveAction(imeOptions, 0)
        )
    }

    @Test
    fun resolveAction_noEnterActionFlag_isNewline() {
        val imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        assertEquals(
            EnterKeyBehavior.ACTION_NEWLINE,
            EnterKeyBehavior.resolveAction(imeOptions, 0)
        )
    }

    @Test
    fun resolveAction_multiLineEditor_isNewlineEvenWithAction() {
        val inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        assertEquals(
            EnterKeyBehavior.ACTION_NEWLINE,
            EnterKeyBehavior.resolveAction(EditorInfo.IME_ACTION_SEND, inputType)
        )
    }

    @Test
    fun resolveAction_imeMultiLineEditor_isNewline() {
        val inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE
        assertEquals(
            EnterKeyBehavior.ACTION_NEWLINE,
            EnterKeyBehavior.resolveAction(EditorInfo.IME_ACTION_SEARCH, inputType)
        )
    }

    @Test
    fun resolveAction_nonTextEditor_notTreatedAsMultiLine() {
        // 多行标志仅对 TYPE_CLASS_TEXT 有意义，其他类别的同位标志不得误判为多行
        val inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        assertEquals(
            EditorInfo.IME_ACTION_DONE,
            EnterKeyBehavior.resolveAction(EditorInfo.IME_ACTION_DONE, inputType)
        )
    }

    @Test
    fun labelForAction_newlineAndActions() {
        assertEquals(
            EnterKeyBehavior.LABEL_NEWLINE,
            EnterKeyBehavior.labelForAction(EnterKeyBehavior.ACTION_NEWLINE)
        )
        assertEquals("搜索", EnterKeyBehavior.labelForAction(EditorInfo.IME_ACTION_SEARCH))
        assertEquals("发送", EnterKeyBehavior.labelForAction(EditorInfo.IME_ACTION_SEND))
        assertEquals("前往", EnterKeyBehavior.labelForAction(EditorInfo.IME_ACTION_GO))
        assertEquals("完成", EnterKeyBehavior.labelForAction(EditorInfo.IME_ACTION_DONE))
    }

    @Test
    fun labelOf_nullEditorInfo_isNewlineLabel() {
        assertEquals(EnterKeyBehavior.LABEL_NEWLINE, EnterKeyBehavior.labelOf(null))
    }

    @Test
    fun labelOf_searchEditor_isSearchLabel() {
        val info = EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_SEARCH }
        assertEquals("搜索", EnterKeyBehavior.labelOf(info))
    }
}
