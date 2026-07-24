package com.ziyou.ime.core.t9

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KeyRecordStack] 九宫格输入状态机回归测试。
 *
 * 核心不变式：**列表顺序 == Rime 编码串逻辑顺序**。
 * 重点验证：选拼音原地替换首个 T9 段、多音节字符偏移累加、智能退格解锁还原、非法/不匹配返回 null。
 */
class KeyRecordStackTest {

    @Test
    fun selectPinyin_locksFirstSegment_atOffsetZero() {
        val stack = KeyRecordStack()
        // 输入 486（对应 guo/gun/huo/hun...）
        stack.pushT9Key('4')
        stack.pushT9Key('8')
        stack.pushT9Key('6')

        val cmd = stack.pushPinyinSelectAction("guo")
        assertEquals(0, cmd!!.caretPos)
        assertEquals(3, cmd.length)
        assertEquals("guo'", cmd.replacement)
    }

    @Test
    fun selectPinyin_secondSyllable_offsetAccumulatesAfterLockedPinyin() {
        val stack = KeyRecordStack()
        stack.pushT9Key('4'); stack.pushT9Key('8'); stack.pushT9Key('6')
        stack.pushPinyinSelectAction("guo") // 锁定第一个音节 guo'（占 4 个字符）

        // 再输入第二段 486
        stack.pushT9Key('4'); stack.pushT9Key('8'); stack.pushT9Key('6')
        val cmd = stack.pushPinyinSelectAction("guo")

        // 已锁定 "guo'" 占 4 字符，第二段从偏移 4 开始
        assertEquals(4, cmd!!.caretPos)
        assertEquals(3, cmd.length)
        assertEquals("guo'", cmd.replacement)
    }

    @Test
    fun popAndRestore_pinyinKey_restoresToDigits() {
        val stack = KeyRecordStack()
        stack.pushT9Key('4'); stack.pushT9Key('8'); stack.pushT9Key('6')
        stack.pushPinyinSelectAction("guo")

        // 退格解锁：拼音段还原为数字段
        val cmd = stack.popAndRestore()
        assertEquals(0, cmd!!.caretPos)
        assertEquals("guo'".length, cmd.length) // 拼音 + 分词符
        assertEquals("486", cmd.replacement)
        // 还原后栈非空（回到 3 个数字键）
        assertFalse(stack.isEmpty())
    }

    @Test
    fun popAndRestore_t9Key_returnsNullAndPops() {
        val stack = KeyRecordStack()
        stack.pushT9Key('4')
        // 栈尾是普通数字键：返回 null（调用方走普通退格），并弹出
        assertNull(stack.popAndRestore())
        assertTrue(stack.isEmpty())
    }

    @Test
    fun pushPinyinSelectAction_unknownPinyin_returnsNull() {
        val stack = KeyRecordStack()
        stack.pushT9Key('4'); stack.pushT9Key('8'); stack.pushT9Key('6')
        assertNull(stack.pushPinyinSelectAction("zzz"))
    }

    @Test
    fun pushPinyinSelectAction_segmentTooShort_returnsNullAndKeepsRecords() {
        val stack = KeyRecordStack()
        // 只输入 48，但 guo 需要 486（3 位），前缀长度不足
        stack.pushT9Key('4'); stack.pushT9Key('8')
        assertNull(stack.pushPinyinSelectAction("guo"))
        // 不匹配时不应破坏已有记录：仍可对正确拼音（hu = 48 → PG? 校验）返回结果
        assertFalse(stack.isEmpty())
    }

    @Test
    fun clear_emptiesStack() {
        val stack = KeyRecordStack()
        stack.pushT9Key('4'); stack.pushApostrophe()
        assertFalse(stack.isEmpty())
        stack.clear()
        assertTrue(stack.isEmpty())
    }
}
