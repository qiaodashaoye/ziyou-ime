package com.ziyou.ime.core

/**
 * Rime引擎数据传输对象
 * 这些类与JNI层的C++ Proto类型一一对应
 * 由native代码通过构造函数创建实例
 */

/** 已提交的文本 */
data class CommitProto(
    val text: String?
) {
    /** JNI层调用的构造函数 */
    constructor() : this(null)
}

/** 候选词条目 */
data class CandidateProto(
    val text: String,
    val comment: String,
    val label: String
) {
    override fun toString(): String = "$label $text${if (comment.isNotEmpty()) " ($comment)" else ""}"
}

/** 编码区信息 */
data class CompositionProto(
    val length: Int,
    val cursorPos: Int,
    val selStart: Int,
    val selEnd: Int,
    val preedit: String?,
    val commitTextPreview: String?
)

/** 菜单（候选词列表）信息 */
data class MenuProto(
    val pageSize: Int,
    val pageNumber: Int,
    val isLastPage: Boolean,
    val highlightedCandidateIndex: Int,
    val candidates: Array<CandidateProto>,
    val selectKeys: String,
    val selectLabels: Array<String>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MenuProto) return false
        return pageSize == other.pageSize &&
                pageNumber == other.pageNumber &&
                isLastPage == other.isLastPage &&
                highlightedCandidateIndex == other.highlightedCandidateIndex &&
                candidates.contentEquals(other.candidates)
    }

    override fun hashCode(): Int {
        var result = pageSize
        result = 31 * result + pageNumber
        result = 31 * result + isLastPage.hashCode()
        result = 31 * result + highlightedCandidateIndex
        result = 31 * result + candidates.contentHashCode()
        return result
    }
}

/** 输入上下文（包含编码和候选词信息） */
data class ContextProto(
    val composition: CompositionProto?,
    val menu: MenuProto?,
    val input: String,
    val caretPos: Int
)

/** 输入法状态 */
data class StatusProto(
    val schemaId: String,
    val schemaName: String,
    val isDisabled: Boolean,
    val isComposing: Boolean,
    val isAsciiMode: Boolean,
    val isFullShape: Boolean,
    val isSimplified: Boolean,
    val isTraditional: Boolean,
    val isAsciiPunct: Boolean
)

/** 方案列表项 */
data class SchemaItem(
    val schemaId: String,
    val name: String
) {
    override fun toString(): String = "$name ($schemaId)"
}

/** 按键事件（备用，对应JNI层的RimeKeyEvent） */
data class RimeKeyEvent(
    val keycode: Int,
    val mask: Int,
    val repr: String
)
