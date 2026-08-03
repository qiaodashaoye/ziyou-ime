package com.ziyou.ime.testing

import com.ziyou.ime.core.CandidateProto
import com.ziyou.ime.core.CommitProto
import com.ziyou.ime.core.ContextProto
import com.ziyou.ime.core.KeyEventResult
import com.ziyou.ime.core.RimeApi
import com.ziyou.ime.core.RimeMessage
import com.ziyou.ime.core.SchemaItem
import com.ziyou.ime.core.StatusProto
import com.ziyou.ime.daemon.RimeEngine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 可配置的 [RimeEngine] 测试替身。
 *
 * 内部持有 [FakeRimeApi]，测试通过设置其属性来控制每次调用的返回值，
 * 并通过各 `*Calls` 列表记录调用历史以供断言。
 */
class FakeRimeEngine(
    override val initialized: Boolean = true
) : RimeEngine {

    override val api = FakeRimeApi()

    private val _messageFlow = MutableSharedFlow<RimeMessage>(replay = 1, extraBufferCapacity = 16)

    override val messageFlow: SharedFlow<RimeMessage> = _messageFlow

    // ===== 生命周期方法（默认空实现，记录调用）=====

    var initializeCalls = 0
    var lastFullCheck: Boolean? = null
    var initializeThrowable: Throwable? = null

    override suspend fun initialize(context: android.content.Context, fullCheck: Boolean) {
        initializeCalls++
        lastFullCheck = fullCheck
        initializeThrowable?.let { throw it }
    }

    var redeployCalls = 0
    override suspend fun redeploy(context: android.content.Context) {
        redeployCalls++
    }

    var destroyCalls = 0
    override suspend fun destroy() {
        destroyCalls++
    }
}

/**
 * 可配置的 [RimeApi] 测试替身。
 *
 * 通过设置各返回值属性控制行为，通过各 `*Calls` 列表记录调用历史。
 */
class FakeRimeApi : RimeApi {

    // ===== 按键处理 =====

    /** processKeyBulk 的返回值序列（按调用顺序消费；耗尽后返回最后一个） */
    var bulkResults = mutableListOf<KeyEventResult>(
        KeyEventResult(consumed = false, commit = null, context = null)
    )

    /** 非空时 processKeyBulk 抛出此异常（模拟引擎故障） */
    var processKeyBulkThrowable: Throwable? = null

    val processKeyBulkCalls = mutableListOf<Pair<Int, Int>>()

    override suspend fun processKeyBulk(keycode: Int, mask: Int): KeyEventResult {
        processKeyBulkCalls.add(keycode to mask)
        processKeyBulkThrowable?.let { throw it }
        return if (bulkResults.isNotEmpty()) {
            if (bulkResults.size == 1) bulkResults[0]
            else bulkResults.removeAt(0)
        } else {
            KeyEventResult(consumed = false, commit = null, context = null)
        }
    }

    // ===== 生命周期 =====

    var startupCalls = 0
    var lastStartupArgs: StartupArgs? = null

    data class StartupArgs(val sharedDir: String, val userDir: String, val version: String, val fullCheck: Boolean)

    override suspend fun startup(sharedDir: String, userDir: String, version: String, fullCheck: Boolean) {
        startupCalls++
        lastStartupArgs = StartupArgs(sharedDir, userDir, version, fullCheck)
    }

    var shutdownCalls = 0
    override suspend fun shutdown() {
        shutdownCalls++
    }

    // ===== 状态查询 =====

    var nextCommit: CommitProto? = null
    var commitCalls = 0
    override suspend fun getCommit(): CommitProto? {
        commitCalls++
        return nextCommit
    }

    var nextContext: ContextProto? = null
    /** getContext 的返回值序列（按调用顺序消费；耗尽后回落到 [nextContext]） */
    val contextQueue = mutableListOf<ContextProto?>()
    var contextCalls = 0
    override suspend fun getContext(): ContextProto? {
        contextCalls++
        return if (contextQueue.isNotEmpty()) contextQueue.removeAt(0) else nextContext
    }

    var nextStatus: StatusProto? = null
    override suspend fun getStatus(): StatusProto? = nextStatus

    var nextCandidates: List<CandidateProto> = emptyList()
    override suspend fun getCandidates(startIndex: Int, limit: Int): List<CandidateProto> = nextCandidates

    // ===== 候选操作 =====

    var selectCandidateResult = true
    val selectCandidateCalls = mutableListOf<Pair<Int, Boolean>>()
    override suspend fun selectCandidate(index: Int, global: Boolean): Boolean {
        selectCandidateCalls.add(index to global)
        return selectCandidateResult
    }

    var deleteCandidateResult = true
    override suspend fun deleteCandidate(index: Int, global: Boolean): Boolean = deleteCandidateResult

    var changePageResult = true
    var lastChangePageBackward: Boolean? = null
    override suspend fun changePage(backward: Boolean): Boolean {
        lastChangePageBackward = backward
        return changePageResult
    }

    // ===== 编码操作 =====

    var commitCompositionResult = true
    override suspend fun commitComposition(): Boolean = commitCompositionResult

    var clearCompositionCalls = 0
    override suspend fun clearComposition() {
        clearCompositionCalls++
    }

    var replaceKeyResult = true
    val replaceKeyCalls = mutableListOf<Triple<Int, Int, String>>()
    override suspend fun replaceKey(caretPos: Int, length: Int, replacement: String): Boolean {
        replaceKeyCalls.add(Triple(caretPos, length, replacement))
        return replaceKeyResult
    }

    override suspend fun processKey(keycode: Int, mask: Int): Boolean {
        val result = processKeyBulk(keycode, mask)
        return result.consumed
    }

    // ===== 方案管理 =====

    var schemaList: List<SchemaItem> = emptyList()
    override suspend fun getSchemaList(): List<SchemaItem> = schemaList

    var currentSchema = "luna_pinyin"
    override suspend fun getCurrentSchema(): String = currentSchema

    var selectSchemaResult = true
    override suspend fun selectSchema(schemaId: String): Boolean {
        currentSchema = schemaId
        return selectSchemaResult
    }

    // ===== 运行时选项 =====

    val setOptionCalls = mutableMapOf<String, Boolean>()
    override suspend fun setOption(key: String, value: Boolean) {
        setOptionCalls[key] = value
    }

    val optionValues = mutableMapOf<String, Boolean>()
    override suspend fun getOption(key: String): Boolean = optionValues[key] ?: false

    // ===== 同步 =====

    var syncResult = true
    override suspend fun syncUserData(): Boolean = syncResult

    // ===== 消息流 =====

    override val messageFlow: SharedFlow<RimeMessage>
        get() = RimeMessageHandlerProxy.messageFlow
}

/** RimeMessageHandler 的只读代理（测试中不实际使用消息流） */
private object RimeMessageHandlerProxy {
    val messageFlow: SharedFlow<RimeMessage> = MutableSharedFlow(replay = 0)
}
