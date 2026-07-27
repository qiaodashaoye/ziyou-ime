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
 * - 部分选词：引擎经 select_candidate 分段确认前缀音节后，用 [InputKey.ConfirmedKey]
 *   **原地合并**栈头对应记录（[confirmLeading]）。原始编码串不变，确认段按其
 *   合并前记录的字符宽度参与偏移计算，保证后续 ReplaceCommand 位置正确。
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
     * 2. 定位列表中首个 [InputKey.T9Key]（即首个未确定音节起点，
     *    天然跳过排在前面的已确认段与已锁定拼音），
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
     * 部分选词后的确认段同步：把栈头「所选候选覆盖的前缀音节」合并为 [InputKey.ConfirmedKey]。
     *
     * 逐音节消费栈头记录（跳过已有确认段）：
     * - 记录为 [InputKey.PinyinKey]：拼音须与音节完全一致；
     * - 记录为 [InputKey.T9Key]：按 [T9PinYinUtils.pinyin2Key] 键序逐位匹配，
     *   允许简拼（至少消费 1 键即可提前结束该音节）；
     * - 音节后紧邻的手动分词符一并并入确认段。
     *
     * @param text      已确认的候选文本（编码区展示用）
     * @param syllables 所选候选的注音音节（来自 spelling_hints comment，如 ["ni"]）
     * @return 是否合并成功；失败时不修改栈（调用方应整栈 clear 降级，宁可降级不可错位）
     */
    fun confirmLeading(text: String, syllables: List<String>): Boolean {
        if (text.isEmpty() || syllables.isEmpty()) return false

        // 跳过已有确认段，从首个未确认记录开始消费
        var start = 0
        while (start < records.size && records[start] is InputKey.ConfirmedKey) start++

        var i = start
        val merged = ArrayList<InputKey>()
        for (syllable in syllables) {
            when (val record = records.getOrNull(i)) {
                is InputKey.PinyinKey -> {
                    // 已锁定拼音须与候选注音一致才可确认
                    if (record.pinyin != syllable) return false
                    merged.add(record)
                    i++
                }
                is InputKey.T9Key -> {
                    val keys = T9PinYinUtils.pinyin2Key(syllable)
                    if (keys.isEmpty()) return false
                    var consumed = 0
                    while (consumed < keys.length) {
                        val r = records.getOrNull(i)
                        if (r !is InputKey.T9Key || r.key != keys[consumed]) break
                        merged.add(r)
                        i++
                        consumed++
                    }
                    // 一个音节至少要消费 1 个击键（支持简拼），否则视为注音与击键不匹配
                    if (consumed == 0) return false
                }
                else -> return false
            }
            // 音节间的手动分词符归属已确认段
            if (records.getOrNull(i) is InputKey.Apostrophe) {
                merged.add(InputKey.Apostrophe)
                i++
            }
        }

        // 原地合并为单个确认段（保持逻辑顺序）
        repeat(i - start) { records.removeAt(start) }
        records.add(start, InputKey.ConfirmedKey(text, merged))
        return true
    }

    /** 栈中是否存在引擎已确认段。 */
    fun hasConfirmed(): Boolean = records.any { it is InputKey.ConfirmedKey }

    /** 前部已确认段在原始编码串中占用的字符总数（无确认段返回 0）。 */
    fun confirmedRawLength(): Int {
        var length = 0
        for (record in records) {
            if (record !is InputKey.ConfirmedKey) break
            length += widthOf(record)
        }
        return length
    }

    /**
     * 未确认部分的原始编码串表示（数字 / 已锁定拼音+分词符 / 分词符），
     * 供「退格重打」路径按引擎编码串逐键重放。
     */
    fun unconfirmedRawChars(): String {
        val sb = StringBuilder()
        for (record in records) {
            if (record is InputKey.ConfirmedKey) continue
            appendRawChars(sb, record)
        }
        return sb.toString()
    }

    /**
     * 解除全部确认段标记（原地展开回合并前记录）。
     * 供调用方在发送 replaceKey 前同步：replaceKey 底层 set_input 会清空引擎内
     * 全部已确认段，栈须同步展开以保持两侧一致；无确认段时为空操作。
     */
    fun unconfirmAll() {
        var i = 0
        while (i < records.size) {
            val record = records[i]
            if (record is InputKey.ConfirmedKey) {
                records.removeAt(i)
                records.addAll(i, record.keys)
            } else {
                i++
            }
        }
    }

    /**
     * 智能回退：
     * - 栈尾是 [InputKey.PinyinKey]：解锁为原 T9 数字段，原地还原并返回替换指令。
     * - 栈尾是 [InputKey.T9Key]：弹出，返回 null（调用方发送普通退格删除末位数字）。
     * - 栈尾是 [InputKey.Apostrophe]：弹出，返回 null。
     * - 栈尾是 [InputKey.ConfirmedKey]：先原地展开回合并前记录（引擎退格触及确认段
     *   原始键时会自行撤销该段确认），再按上述规则处理展开后的栈尾。
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
            is InputKey.ConfirmedKey -> {
                // 展开确认段后递归处理原始栈尾记录
                records.removeAt(lastIndex)
                records.addAll(lastIndex, top.keys)
                popAndRestore()
            }
        }
    }

    /**
     * 计算 [index] 之前所有元素在编码串中占用的字符数。
     * 已锁定拼音占 `拼音长度 + 分词符`，数字键 / 分词符各占 1，
     * 确认段按其合并前记录累加（原始编码串未变）。
     */
    private fun charOffsetBefore(index: Int): Int {
        var pos = 0
        for (i in 0 until index) {
            pos += widthOf(records[i])
        }
        return pos
    }

    /** 单条记录在原始编码串中占用的字符数。 */
    private fun widthOf(key: InputKey): Int = when (key) {
        is InputKey.T9Key -> 1
        is InputKey.Apostrophe -> 1
        is InputKey.PinyinKey -> key.pinyin.length + DELIMITER.length
        is InputKey.ConfirmedKey -> key.keys.sumOf { widthOf(it) }
    }

    /** 追加单条记录的原始编码串表示。 */
    private fun appendRawChars(sb: StringBuilder, key: InputKey) {
        when (key) {
            is InputKey.T9Key -> sb.append(key.key)
            is InputKey.Apostrophe -> sb.append(DELIMITER)
            is InputKey.PinyinKey -> sb.append(key.pinyin).append(DELIMITER)
            is InputKey.ConfirmedKey -> key.keys.forEach { appendRawChars(sb, it) }
        }
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

    /**
     * 引擎已确认段（部分选词后由 select_candidate 分段确认的前缀）。
     *
     * @param text 已确认的候选文本（如 "你"），供编码区展示已确认前缀
     * @param keys 合并前的原始记录（T9Key / Apostrophe / PinyinKey），
     *             原始编码串未变，偏移与退格还原均以此为准
     */
    data class ConfirmedKey(val text: String, val keys: List<InputKey>) : InputKey()
}
