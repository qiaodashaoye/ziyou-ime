package com.ziyou.ime.core.voice

/**
 * 语音输入会话的文本缓冲策略（纯逻辑，不依赖任何 Android 类型）。
 *
 * 流式识别的结果分两路，本缓冲把二者严格隔离：
 * - partial（中间结果）：只用于面板预览渲染，**永远不直接上屏**；
 *   识别引擎会反复改写 partial（识别纠错），直接上屏会把错字写进编辑器。
 * - final（端点确认段）：累积进已确认区，调用方经 [drainConfirmed]
 *   取走增量后经直达路由上屏（drain 语义：取过即清，绝不重复投递）。
 *
 * 调用纪律：final 到达时调 [commitSegment]；预览文本一律经 [preview]
 * （已确认 + 当前 partial）渲染，禁止自行拼接。
 */
class VoiceUtteranceBuffer {

    private val confirmedSegments = mutableListOf<String>()

    /** 已被 drain 取走的段数（上屏进度）。 */
    private var drainedUpTo = 0

    private var partial: String = ""

    /**
     * 更新中间结果。
     *
     * @return 是否发生变化（与上次相同则返回 false，调用方可据此跳过 UI 重绘）
     */
    fun updatePartial(text: String): Boolean {
        val normalized = text.trim()
        if (normalized == partial) return false
        partial = normalized
        return true
    }

    /** 当前中间结果（已 trim，可能为空串）。 */
    fun currentPartial(): String = partial

    /**
     * 落入一个 final 确认段（端点确认的一句话）。落段即清空 partial。
     *
     * @return 是否有效落入（空白段被忽略返回 false）
     */
    fun commitSegment(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        confirmedSegments += trimmed
        partial = ""
        return true
    }

    /** 面板预览文本 = 全部已确认段 + 当前中间结果（中文场景直接拼接，不加分隔符）。 */
    fun preview(): String = buildString {
        confirmedSegments.forEach { append(it) }
        append(partial)
    }

    /**
     * 取走尚未上屏的已确认增量文本（drain 语义）。
     *
     * @return 增量拼接串；无增量时返回空串
     */
    fun drainConfirmed(): String {
        if (drainedUpTo >= confirmedSegments.size) return ""
        val text = confirmedSegments.subList(drainedUpTo, confirmedSegments.size).joinToString("")
        drainedUpTo = confirmedSegments.size
        return text
    }

    /** 是否存在未上屏的已确认增量。 */
    fun hasPendingConfirmed(): Boolean = drainedUpTo < confirmedSegments.size

    /** 会话结束/重新开始时全量复位。 */
    fun reset() {
        confirmedSegments.clear()
        drainedUpTo = 0
        partial = ""
    }
}
