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
 * 重点验证：选拼音原地替换首个 T9 段、多音节字符偏移累加、智能退格解锁还原、
 * 分段确认（confirmLeading）合并与偏移跳过、非法/不匹配返回 null。
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

    // ===== 分段确认（confirmLeading）=====

    @Test
    fun confirmLeading_digits_mergesAndSkipsConfirmedInOffsets() {
        val stack = KeyRecordStack()
        // nihao 击键 64426（ni=64, hao=426），选“你”分段确认
        "64426".forEach { stack.pushT9Key(it) }
        assertTrue(stack.confirmLeading("你", listOf("ni")))

        assertTrue(stack.hasConfirmed())
        assertEquals(2, stack.confirmedRawLength())
        assertEquals("426", stack.unconfirmedRawChars())

        // 后续侧栏选拼音针对首个未确认段：偏移跳过已确认原始键
        val cmd = stack.pushPinyinSelectAction("hao")
        assertEquals(2, cmd!!.caretPos)
        assertEquals(3, cmd.length)
        assertEquals("hao'", cmd.replacement)
        assertEquals("hao'", stack.unconfirmedRawChars())
    }

    @Test
    fun confirmLeading_lockedPinyin_consumesPinyinKey() {
        val stack = KeyRecordStack()
        "486".forEach { stack.pushT9Key(it) }
        stack.pushPinyinSelectAction("guo") // 先锁定 guo'
        assertTrue(stack.confirmLeading("国", listOf("guo")))

        // 确认段宽度按合并前记录计：guo + 分词符共 4 字符
        assertEquals(4, stack.confirmedRawLength())
        assertEquals("", stack.unconfirmedRawChars())
    }

    @Test
    fun confirmLeading_mismatch_returnsFalseKeepsStack() {
        val stack = KeyRecordStack()
        "486".forEach { stack.pushT9Key(it) }
        // ma 的键序 62 与首键 4 不匹配：失败且不修改栈
        assertFalse(stack.confirmLeading("妈", listOf("ma")))
        assertFalse(stack.hasConfirmed())
        assertEquals("486", stack.unconfirmedRawChars())
    }

    @Test
    fun popAndRestore_confirmedDigits_explodesAndPopsLast() {
        val stack = KeyRecordStack()
        "64".forEach { stack.pushT9Key(it) }
        stack.confirmLeading("你", listOf("ni"))

        // 引擎退格会删确认段末位原始键并自行撤销确认；栈同步展开后弹出末位
        assertNull(stack.popAndRestore())
        assertFalse(stack.hasConfirmed())
        assertEquals("6", stack.unconfirmedRawChars())
    }

    @Test
    fun popAndRestore_confirmedLockedPinyin_returnsRestoreCommand() {
        val stack = KeyRecordStack()
        "486".forEach { stack.pushT9Key(it) }
        stack.pushPinyinSelectAction("guo")
        stack.confirmLeading("国", listOf("guo"))

        // 展开后栈尾是已锁定拼音：返回替换指令还原为原 T9 段
        val cmd = stack.popAndRestore()
        assertEquals(0, cmd!!.caretPos)
        assertEquals("guo'".length, cmd.length)
        assertEquals("486", cmd.replacement)
        assertFalse(stack.hasConfirmed())
    }

    @Test
    fun unconfirmAll_restoresRawOrderAndOffsets() {
        val stack = KeyRecordStack()
        "64426".forEach { stack.pushT9Key(it) }
        stack.confirmLeading("你", listOf("ni"))

        stack.unconfirmAll()

        assertFalse(stack.hasConfirmed())
        assertEquals(0, stack.confirmedRawLength())
        assertEquals("64426", stack.unconfirmedRawChars())
        // 展开后首段可正常锁拼音（偏移从 0 开始）
        val cmd = stack.pushPinyinSelectAction("ni")
        assertEquals(0, cmd!!.caretPos)
        assertEquals("ni'", cmd.replacement)
    }
}
