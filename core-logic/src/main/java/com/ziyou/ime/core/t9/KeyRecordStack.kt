package com.ziyou.ime.core.t9

import com.ziyou.ime.util.T9PinYinUtils

/**
 * 九宫格输入状态机，追踪 T9 按键与已锁定拼音，支持拼音消歧与智能回退。
 *
 * 设计要点（修复多音节位置错乱的核心）：
 * **列表顺序 == Rime 编码串的逻辑顺序**。
 * - 键入数字：[T9Key] 追加到列表尾部（Rime 也把数字追加到编码串尾部）。
 * - 选定拼音：用 [InputKey.PinyinKey] **原地替换**首个 T9Key 段（而非追加到末尾），
 *   因此已锁定拼音始终排在剩余数字之前，字符偏移可按列表顺序直接累加得到。
 * - 智能退格：解锁列表尾部的拼音，原地还原为数字段。
 *
 * 纯数据结构，不依赖 Android Framework，非线程安全（仅在主线程访问）。
 */
class KeyRecordStack {

    private val records = ArrayList<InputKey>(20)

    fun isEmpty(): Boolean = records.isEmpty()

    fun clear() = records.clear()

    /**
     * 将 T9 按键推入栈（'2'-'9'）。
     * 对应 Rime 把该数字追加到编码串尾部。
     */
    fun pushT9Key(keyChar: Char) {
        records.add(InputKey.T9Key(keyChar))
    }

    /**
     * 将分词键（撇号）推入栈。
     */
    fun pushApostrophe() {
        records.add(InputKey.Apostrophe)
    }

    /**
     * 选定拼音：锁定「首个未确定音节」对应的 T9 数字段。
     *
     * 步骤：
     * 1. 用 [T9PinYinUtils.pinyin2Key] 取拼音对应的 T9 键序列。
     * 2. 定位列表中首个 [InputKey.T9Key]（即首个未确定音节起点），
     *    并校验其后连续 T9Key 的前缀与该键序列一致。
     * 3. 计算该段在编码串中的字符偏移。
     * 4. 用 [InputKey.PinyinKey] 原地替换匹配的 T9Key 段（保持逻辑顺序）。
     * 5. 返回替换指令，供调用方 `Rime.replaceKey`。
     *
     * @return 替换指令；无法匹配返回 null（调用方应忽略本次选择）。
     */
    fun pushPinyinSelectAction(pinyin: String): ReplaceCommand? {
        val t9Sequence = T9PinYinUtils.pinyin2Key(pinyin)
        if (t9Sequence.isEmpty()) return null

        // 首个 T9Key 即首个未确定音节的起点（已锁定拼音永远排在前面）
        val start = records.indexOfFirst { it is InputKey.T9Key }
        if (start < 0) return null

        // 校验该数字段前缀与拼音的 T9 序列逐位一致，且长度足够
        if (start + t9Sequence.length > records.size) return null
        for (i in t9Sequence.indices) {
            val record = records[start + i]
            if (record !is InputKey.T9Key || record.key != t9Sequence[i]) return null
        }

        val caretPos = charOffsetBefore(start)

        // 用 PinyinKey 原地替换匹配的 T9Key 段
        repeat(t9Sequence.length) { records.removeAt(start) }
        records.add(start, InputKey.PinyinKey(pinyin))

        return ReplaceCommand(
            caretPos = caretPos,
            length = t9Sequence.length,
            replacement = pinyin + DELIMITER
        )
    }

    /**
     * 智能回退：
     * - 栈尾是 [InputKey.PinyinKey]：解锁为原 T9 数字段，原地还原并返回替换指令。
     * - 栈尾是 [InputKey.T9Key]：弹出，返回 null（调用方发送普通退格删除末位数字）。
     * - 栈尾是 [InputKey.Apostrophe]：弹出，返回 null。
     */
    fun popAndRestore(): ReplaceCommand? {
        if (records.isEmpty()) return null

        val lastIndex = records.size - 1
        return when (val top = records[lastIndex]) {
            is InputKey.PinyinKey -> {
                val t9Sequence = T9PinYinUtils.pinyin2Key(top.pinyin)
                val caretPos = charOffsetBefore(lastIndex)
                // 原地还原：拼音 → 对应 T9Key 段
                records.removeAt(lastIndex)
                t9Sequence.forEachIndexed { i, c ->
                    records.add(lastIndex + i, InputKey.T9Key(c))
                }
                ReplaceCommand(
                    caretPos = caretPos,
                    length = top.pinyin.length + DELIMITER.length,  // 拼音 + 分词符
                    replacement = t9Sequence
                )
            }
            is InputKey.T9Key -> {
                records.removeAt(lastIndex)
                null
            }
            is InputKey.Apostrophe -> {
                records.removeAt(lastIndex)
                null
            }
        }
    }

    /**
     * 计算 [index] 之前所有元素在编码串中占用的字符数。
     * 已锁定拼音占 `拼音长度 + 分词符`，数字键 / 分词符各占 1。
     */
    private fun charOffsetBefore(index: Int): Int {
        var pos = 0
        for (i in 0 until index) {
            pos += when (val element = records[i]) {
                is InputKey.T9Key -> 1
                is InputKey.Apostrophe -> 1
                is InputKey.PinyinKey -> element.pinyin.length + DELIMITER.length
            }
        }
        return pos
    }

    companion object {
        /** Rime 编码分词符（与 t9.schema.yaml 的 delimiter 一致） */
        private const val DELIMITER = "'"
    }
}

/**
 * 编码替换指令，供调用方向 Rime 发送 `replaceKey`。
 *
 * @param caretPos    在 Rime 编码串中的起始字符位置
 * @param length      要替换的字符长度
 * @param replacement 替换后的字符串
 */
data class ReplaceCommand(
    val caretPos: Int,
    val length: Int,
    val replacement: String
)

/**
 * 输入键的密封类层级，仅保留 T9 相关路径。
 */
sealed class InputKey {
    /**
     * T9 按键（'2'-'9'）
     */
    data class T9Key(val key: Char) : InputKey()

    /**
     * 分词键（撇号）
     */
    data object Apostrophe : InputKey()

    /**
     * 已锁定拼音（首个未确定音节被用户选定后固定下来）
     */
    data class PinyinKey(val pinyin: String) : InputKey()
}
