package com.ziyou.ime.sdk.state

import com.ziyou.ime.core.CandidateProto
import com.ziyou.ime.core.ContextProto
import com.ziyou.ime.core.RimeApi
import com.ziyou.ime.daemon.RimeEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 编码区（Preedit）状态快照。
 *
 * 由引擎 [ContextProto.composition] 派生，是编码区渲染的唯一事实源。
 * 空值规范：[rawText] 为空串 ⇒ 无活跃编码（UI 层据此置 GONE / 清空，
 * 见 PreeditOverlayView 空值规范）。
 */
data class PreeditState(
    /** 原始编码串（含分词符），空串表示无编码 */
    val rawText: String,
    /** 光标位置（码点计） */
    val caretPos: Int,
    /** 已确认（选中）片段起点（码点计） */
    val selStart: Int,
    /** 已确认（选中）片段终点（码点计） */
    val selEnd: Int,
    /** 提交预览文本（引擎对当前编码的整句预览，可空） */
    val commitPreview: String?
) {
    val isEmpty: Boolean get() = rawText.isEmpty()

    companion object {
        val EMPTY = PreeditState("", 0, 0, 0, null)

        /** 从引擎上下文派生；null 上下文 / 无 composition 均为空态。 */
        fun from(context: ContextProto?): PreeditState {
            val composition = context?.composition ?: return EMPTY
            return PreeditState(
                rawText = composition.preedit.orEmpty(),
                caretPos = composition.cursorPos,
                selStart = composition.selStart,
                selEnd = composition.selEnd,
                commitPreview = composition.commitTextPreview
            )
        }
    }
}

/**
 * 编码区状态管理（SDK）。
 *
 * 状态经 [state] 以 [StateFlow] 交付，UI 层可直接 collect 渲染，无需解析
 * Proto 细节；变更由 [InputSession][com.ziyou.ime.sdk.input.InputSession]
 * 在每次引擎上下文刷新时经 [refreshFrom] 内部驱动，应用层无需手动喂数据。
 *
 * 注：本服务承载**引擎原始编码**状态。九宫格拼音预览（数字段还原拼音）等
 * 宿主增强叠加在应用层完成（见宿主 PinyinHintProvider），二者正交。
 */
class PreeditController internal constructor(
    private val api: RimeApi
) {

    companion object {
        /** 公开工厂：基于引擎创建编码区状态服务。 */
        fun newInstance(engine: RimeEngine): PreeditController = PreeditController(engine.api)
    }

    private val _state = MutableStateFlow(PreeditState.EMPTY)

    /** 编码区唯一事实源（主线程可直接 collect）。 */
    val state: StateFlow<PreeditState> = _state.asStateFlow()

    /** 内部：引擎上下文变更后刷新快照（由 InputSession 调用）。 */
    internal fun refreshFrom(context: ContextProto?) {
        _state.value = PreeditState.from(context)
    }

    /** 清除当前编码（等价 Escape 语义的编码清空）。 */
    suspend fun clear() {
        api.clearComposition()
    }

    /** 提交当前编码（整句/当前候选直接上屏语义由引擎决定）。 */
    suspend fun commit(): Boolean = api.commitComposition()

    /**
     * 替换编码串片段（九宫格消歧底层原语的通用形式）。
     * 高阶封装见 [InputSession.selectPinyin / restorePinyin]。
     */
    suspend fun replaceSegment(caretPos: Int, length: Int, replacement: String): Boolean =
        api.replaceKey(caretPos, length, replacement)
}

/**
 * 候选词快照：当前页候选 + 总数 + 高亮索引 + 联想态标记。
 *
 * [isPrediction]：引擎预测态（librime-predict：commit 后菜单非空且编码为空），
 * UI 层据此以强调色整栏绘制区分。
 */
data class CandidatesSnapshot(
    val items: List<CandidateProto>,
    val total: Int,
    val highlightedIndex: Int,
    val pageNumber: Int,
    val pageSize: Int,
    val isPrediction: Boolean
) {
    companion object {
        val EMPTY = CandidatesSnapshot(emptyList(), 0, -1, 0, 0, false)

        /** 从引擎上下文派生。 */
        fun from(context: ContextProto?): CandidatesSnapshot {
            val menu = context?.menu
            val items = menu?.candidates?.toList().orEmpty()
            return CandidatesSnapshot(
                items = items,
                total = items.size,
                highlightedIndex = menu?.highlightedCandidateIndex ?: -1,
                pageNumber = menu?.pageNumber ?: 0,
                pageSize = menu?.pageSize ?: 0,
                isPrediction = items.isNotEmpty() && context?.input?.isEmpty() == true
            )
        }
    }
}

/**
 * 候选词查询与管理（SDK）。
 *
 * 当前页快照经 [snapshot] 以 [StateFlow] 交付（由 InputSession 在每次引擎
 * 上下文刷新时内部驱动）；深翻页懒加载经 [query]。选词/删词/翻页动作为
 * 引擎原语的通用封装，九宫格分段确认等业务语义仍由宿主输入管线承担。
 */
class CandidatesService internal constructor(
    private val api: RimeApi
) {

    companion object {
        /** 公开工厂：基于引擎创建候选词服务。 */
        fun newInstance(engine: RimeEngine): CandidatesService = CandidatesService(engine.api)
    }

    private val _snapshot = MutableStateFlow(CandidatesSnapshot.EMPTY)

    /** 当前上下文候选快照（主线程可直接 collect）。 */
    val snapshot: StateFlow<CandidatesSnapshot> = _snapshot.asStateFlow()

    /** 内部：引擎上下文变更后刷新快照（由 InputSession 调用）。 */
    internal fun refreshFrom(context: ContextProto?) {
        _snapshot.value = CandidatesSnapshot.from(context)
    }

    /** 懒加载深翻页：按起始索引与条数取候选（累积缓冲之外的页）。 */
    suspend fun query(startIndex: Int, limit: Int): List<CandidateProto> =
        api.getCandidates(startIndex, limit)

    /** 选择候选。@param global true=全局索引（跨页累积缓冲），false=页内索引 */
    suspend fun select(index: Int, global: Boolean = false): Boolean =
        api.selectCandidate(index, global)

    /** 删除候选（用户词删除等）。@param global 同 [select] */
    suspend fun delete(index: Int, global: Boolean = false): Boolean =
        api.deleteCandidate(index, global)

    /** 翻页。@param backward true=上一页 */
    suspend fun changePage(backward: Boolean): Boolean =
        api.changePage(backward)
}

/**
 * 方案 / 选项 / 用户数据管理（SDK）。
 *
 * 引擎 [RimeApi] 方案与选项能力的通用封装；默认方案选择等宿主策略
 * （如 SchemaPreference 的 DEFAULT_SCHEMA_ID 契约）留在应用层。
 */
class SchemaService internal constructor(
    private val api: RimeApi
) {
    companion object {
        /** 公开工厂：基于引擎创建方案服务。 */
        fun newInstance(engine: RimeEngine): SchemaService = SchemaService(engine.api)
    }
    /** 全部可用方案。 */
    suspend fun list() = api.getSchemaList()

    /** 当前方案 id。 */
    suspend fun current(): String = api.getCurrentSchema()

    /** 切换方案（触发引擎重部署语义由引擎决定）。 */
    suspend fun select(schemaId: String): Boolean = api.selectSchema(schemaId)

    /** 设置运行时选项（如 ascii_mode / prediction）。 */
    suspend fun setOption(key: String, value: Boolean) = api.setOption(key, value)

    /** 读取运行时选项。 */
    suspend fun getOption(key: String): Boolean = api.getOption(key)

    /** 同步用户数据（userdb）。 */
    suspend fun syncUserData(): Boolean = api.syncUserData()
}
