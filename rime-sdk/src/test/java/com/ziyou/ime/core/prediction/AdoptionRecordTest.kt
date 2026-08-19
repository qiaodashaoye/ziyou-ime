package com.ziyou.ime.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AdoptionRecord] 单元测试（联想优化方案 §4.6 形态 B 数据层）。
 *
 * 隐私边界验收重点：仅 1~4 字纯汉字词对可入记录（isLearnableWord），
 * 标点/英文/数字/超长词一律过滤——学习数据不含语句上下文。
 */
class AdoptionRecordTest {

    @Test
    fun `可学习词判定仅放行1至4字纯汉字`() {
        assertTrue(AdoptionRecord.isLearnableWord("好"))
        assertTrue(AdoptionRecord.isLearnableWord("今天"))
        assertTrue(AdoptionRecord.isLearnableWord("天气不错"))
        // 扩展 A 区生僻字同样视为汉字
        assertTrue(AdoptionRecord.isLearnableWord("\u3400"))
        assertFalse(AdoptionRecord.isLearnableWord(""))
        assertFalse(AdoptionRecord.isLearnableWord("五个字不行呀"))
        assertFalse(AdoptionRecord.isLearnableWord("好的。"))
        assertFalse(AdoptionRecord.isLearnableWord("ok"))
        assertFalse(AdoptionRecord.isLearnableWord("123"))
        assertFalse(AdoptionRecord.isLearnableWord("中a混b"))
    }

    @Test
    fun `record累计计数且非法词静默忽略`() {
        val record = AdoptionRecord()
        record.record("今天", "天气")
        record.record("今天", "天气")
        record.record("今天", "开心")
        assertEquals(2, record.snapshot()["今天"]?.get("天气"))
        assertEquals(1, record.snapshot()["今天"]?.get("开心"))

        // 非法输入不产生记录：标点词、英文、超长词、空串
        record.record("好的。", "收到")
        record.record("今天", "ok")
        record.record("今天", "超过四个字的词组")
        record.record("", "收到")
        assertEquals(1, record.snapshot().size)
        assertEquals(2, record.size())
    }

    @Test
    fun `next自带标点时剥离后仍可记录`() {
        val record = AdoptionRecord()
        // 续写候选常带句尾标点：剥离标点归一后再判定与存储
        record.record("今天", "天气。")
        assertEquals(1, record.snapshot()["今天"]?.get("天气"))
    }

    @Test
    fun `自回环词对被拒绝`() {
        val record = AdoptionRecord()
        record.record("今天", "今天")
        assertEquals(0, record.size())
    }

    @Test
    fun `尾词超容量时淘汰计数最小者`() {
        val record = AdoptionRecord()
        // 纯汉字尾词（含数字的词会被学习过滤器拒绝，不入记录）
        val tails = listOf("尾甲", "尾乙", "尾丙", "尾丁", "尾戊", "尾己", "尾庚", "尾辛")
        tails.forEach { record.record("头词", it) }
        record.record("头词", "新尾")
        val saved = record.snapshot()["头词"]!!
        assertEquals(AdoptionRecord.MAX_TAILS_PER_HEAD, saved.size)
        assertNull(saved["尾甲"])
        assertEquals(1, saved["新尾"])
        // 高计数者不会被淘汰：给「尾乙」加权后再挤入一条
        record.record("头词", "尾乙")
        record.record("头词", "再新尾")
        assertEquals(2, record.snapshot()["头词"]?.get("尾乙"))
    }

    @Test
    fun `头词超容量时淘汰最旧插入者`() {
        val record = AdoptionRecord()
        // 26 字字符集的两两组合足够生成 501 个互异纯汉字头词
        val chars = "甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未申酉戌亥春夏秋冬"
        var generated = 0
        outer@ for (c1 in chars) {
            for (c2 in chars) {
                record.record("头$c1$c2", "尾")
                if (++generated > AdoptionRecord.MAX_HEADS) break@outer
            }
        }
        assertEquals(AdoptionRecord.MAX_HEADS, record.snapshot().size)
        assertNull(record.snapshot()["头甲甲"])
        assertEquals(1, record.snapshot()["头甲乙"]?.get("尾"))
    }

    @Test
    fun `snapshot与restore往返一致且restore过滤脏数据`() {
        val record = AdoptionRecord()
        record.record("今天", "天气")
        record.record("收到", "谢谢")
        val snapshot = record.snapshot()

        val restored = AdoptionRecord()
        // 混入脏数据：非法头词、非法尾词、自回环、非正计数
        val dirty = snapshot + mapOf(
            "坏词。" to mapOf("尾" to 1),
            "好头" to mapOf("坏尾!" to 1, "好头" to 2, "好尾" to 0, "可用尾" to 3)
        )
        restored.restore(dirty)
        assertEquals(1, restored.snapshot()["今天"]?.get("天气"))
        assertNull(restored.snapshot()["坏词。"])
        assertEquals(mapOf("可用尾" to 3), restored.snapshot()["好头"])
    }

    @Test
    fun `restore后脏标记复位而record置脏`() {
        val record = AdoptionRecord()
        record.restore(mapOf("今天" to mapOf("天气" to 2)))
        assertFalse(record.isDirty())
        record.record("收到", "谢谢")
        assertTrue(record.isDirty())
    }

    @Test
    fun `drainSince导出全量并清空`() {
        val record = AdoptionRecord()
        record.record("今天", "天气")
        val drained = record.drainSince()
        assertEquals(1, drained["今天"]?.get("天气"))
        assertEquals(0, record.size())
        assertFalse(record.isDirty())
    }

    @Test
    fun `clear清空全部记录并复位脏标记`() {
        val record = AdoptionRecord()
        record.record("今天", "天气")
        record.clear()
        assertEquals(0, record.size())
        assertFalse(record.isDirty())
    }
}
