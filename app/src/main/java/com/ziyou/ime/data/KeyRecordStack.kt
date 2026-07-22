package com.ziyou.ime.data

import com.ziyou.ime.util.T9PinYinUtils

/**
 * 九宫格输入状态机，追踪 T9 按键历史，支持拼音选择与智能回退。
 * 纯数据结构，不依赖 Android Framework，非线程安全。
 */
class KeyRecordStack {

    private val keyRecords = ArrayList<InputKey>(20)

    fun isEmpty(): Boolean = keyRecords.isEmpty()

    fun clear() = keyRecords.clear()

    /**
     * 将 T9 按键推入栈（'2'-'9'）
     */
    fun pushT9Key(keyChar: Char) {
        keyRecords.add(InputKey.T9Key(keyChar))
    }

    /**
     * 将分词键（撇号）推入栈
     */
    fun pushApostrophe() {
        keyRecords.add(InputKey.Apostrophe)
    }

    /**
     * 拼音选择操作：
     * 1. 使用 T9PinYinUtils.pinyin2Key 获取该拼音对应的 T9 键序列
     * 2. 在栈中从头查找连续未消费的 T9Key 序列（匹配该 T9 键序列长度）
     * 3. 将这些 T9Key 标记为 consumed
     * 4. 计算 posInInput（在整个编码字符串中的位置）
     * 5. 创建 PinyinKey 并推入栈
     * 6. 返回该 PinyinKey（调用方用于 Rime.replaceKey）
     */
    fun pushPinyinSelectAction(pinyin: String): InputKey.PinyinKey? {
        val t9Sequence = T9PinYinUtils.pinyin2Key(pinyin)
        if (t9Sequence.isEmpty()) return null

        val t9Chars = t9Sequence.toCharArray()
        val seqLen = t9Chars.size

        // 在栈中从头查找连续未消费的 T9Key 序列
        val startIndex = findUnconsumedT9Sequence(t9Chars)
        if (startIndex < 0) return null

        // 将匹配的 T9Key 标记为 consumed
        for (i in 0 until seqLen) {
            (keyRecords[startIndex + i] as InputKey.T9Key).consumed = true
        }

        // 计算 posInInput：此 PinyinKey 之前所有元素在编码中占用的字符数
        val posInInput = calculatePosInInput(startIndex)

        val pinyinKey = InputKey.PinyinKey(
            pinyin = pinyin,
            posInInput = posInInput,
            t9KeysLength = seqLen
        )
        keyRecords.add(pinyinKey)
        return pinyinKey
    }

    /**
     * 撤销最近操作：
     * - 栈顶是 PinyinKey：弹出，恢复对应 T9Key 为未消费状态，返回 RestoreResult
     * - 栈顶是 T9Key：弹出，返回 null（调用方发送普通 BackSpace）
     * - 栈顶是 Apostrophe：弹出，返回 null
     */
    fun popAndRestore(): RestoreResult? {
        if (keyRecords.isEmpty()) return null

        return when (val top = keyRecords.removeLast()) {
            is InputKey.PinyinKey -> {
                // 恢复对应的被消费 T9Key 为未消费状态
                val t9Sequence = T9PinYinUtils.pinyin2Key(top.pinyin)
                var restored = 0
                val targetCount = top.t9KeysLength

                // 从头遍历找到对应位置的 consumed T9Key 并恢复
                for (record in keyRecords) {
                    if (restored >= targetCount) break
                    if (record is InputKey.T9Key && record.consumed) {
                        record.consumed = false
                        restored++
                    }
                }

                RestoreResult(
                    posInInput = top.posInInput,
                    length = top.pinyin.length + 1,  // 拼音 + 分词符
                    t9Keys = t9Sequence
                )
            }
            is InputKey.T9Key -> null
            is InputKey.Apostrophe -> null
        }
    }

    /**
     * 在栈中从头查找连续未消费的 T9Key 序列，返回起始索引；找不到返回 -1
     */
    private fun findUnconsumedT9Sequence(t9Chars: CharArray): Int {
        val seqLen = t9Chars.size
        if (keyRecords.size < seqLen) return -1

        for (start in 0..keyRecords.size - seqLen) {
            val matched = (0 until seqLen).all { j ->
                val record = keyRecords[start + j]
                record is InputKey.T9Key && !record.consumed && record.key == t9Chars[j]
            }
            if (matched) return start
        }
        return -1
    }

    /**
     * 计算在编码字符串中指定位置之前的所有元素占用字符数
     */
    private fun calculatePosInInput(upToIndex: Int): Int {
        var pos = 0
        for (i in 0 until upToIndex) {
            pos += when (val element = keyRecords[i]) {
                is InputKey.T9Key -> if (!element.consumed) 1 else 0
                is InputKey.Apostrophe -> 1
                is InputKey.PinyinKey -> element.pinyin.length + 1  // 拼音 + 分词符
            }
        }
        return pos
    }
}

/**
 * 回退操作的结果，用于调用方向 Rime 发送替换指令
 */
data class RestoreResult(
    val posInInput: Int,    // 在编码中的位置
    val length: Int,        // 要替换的长度（拼音+分词符）
    val t9Keys: String      // 要恢复的 T9 键序列
)

/**
 * 输入键的密封类层级，仅保留 T9 相关路径
 */
sealed class InputKey {
    /**
     * T9 按键（'2'-'9'）
     */
    data class T9Key(val key: Char, var consumed: Boolean = false) : InputKey()

    /**
     * 分词键（撇号）
     */
    data object Apostrophe : InputKey()

    /**
     * 已选拼音，记录在编码中的位置和原 T9 键长度
     */
    data class PinyinKey(
        val pinyin: String,
        val posInInput: Int,
        val t9KeysLength: Int
    ) : InputKey()
}
