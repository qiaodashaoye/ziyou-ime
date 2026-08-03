package com.ziyou.ime.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.ziyou.ime.core.CommitProto
import com.ziyou.ime.core.CompositionProto
import com.ziyou.ime.core.ContextProto
import com.ziyou.ime.core.KeyEventResult
import com.ziyou.ime.core.MenuProto
import com.ziyou.ime.core.t9.KeyRecordStack
import com.ziyou.ime.di.AppContainer
import com.ziyou.ime.testing.FakeRimeEngine
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [InputLogicController.processKey] 热路径测试。
 *
 * 通过 [AppContainer.overrideRimeEngine] 注入 [FakeRimeEngine]，覆盖：
 * - 按键被 Rime 消费 → commit 上屏 + 清栈 + 刷新 UI
 * - 按键未消费 → 退格删字 / 回车路由面板 / 可打印字符直接提交
 * - commitTarget 路由（技能面板 vs 宿主编辑器）
 * - commitListeners 计分回调
 * - 异常容错
 * - inputMutex 串行化
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InputLogicControllerTest {

    private lateinit var fakeEngine: FakeRimeEngine
    private lateinit var controller: InputLogicController
    private lateinit var keyRecordStack: KeyRecordStack
    private lateinit var inputConnection: InputConnection
    private lateinit var callbacks: TestCallbacks
    private val commitListenerCalls = mutableListOf<Int>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        fakeEngine = FakeRimeEngine()
        // 通过 DI 测试钩子注入 fake 引擎
        AppContainer.overrideRimeEngine(fakeEngine)

        keyRecordStack = KeyRecordStack()
        inputConnection = mockk(relaxed = true)
        callbacks = TestCallbacks(inputConnection)
        commitListenerCalls.clear()

        controller = InputLogicController(
            engine = AppContainer.rimeEngine,
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            keyRecordStack = keyRecordStack,
            callbacks = callbacks,
            commitListeners = listOf { codePoints -> commitListenerCalls.add(codePoints) }
        )
    }

    @After
    fun tearDown() {
        AppContainer.overrideRimeEngine(null)
        Dispatchers.resetMain()
    }

    // ===== 按键被 Rime 消费 =====

    @Test
    fun processKey_consumedWithCommit_commitsTextClearsStackRendersContext() = runTest {
        val context = testContext(input = "ni", candidates = listOf("你" to "ni"))
        fakeEngine.api.bulkResults = mutableListOf(
            KeyEventResult(consumed = true, commit = CommitProto("你"), context = context)
        )
        keyRecordStack.pushT9Key('6')
        keyRecordStack.pushT9Key('4')
        assertFalse(keyRecordStack.isEmpty())

        controller.processKey('n'.code, 0)

        verify { inputConnection.commitText("你", 1) }
        assertTrue(keyRecordStack.isEmpty())
        assertEquals(context, callbacks.lastRenderedContext)
    }

    @Test
    fun processKey_consumedWithCommit_commitListenerCalledWithCodePoints() = runTest {
        fakeEngine.api.bulkResults = mutableListOf(
            KeyEventResult(consumed = true, commit = CommitProto("你好世界"), context = null)
        )

        controller.processKey('n'.code, 0)

        assertEquals(listOf(4), commitListenerCalls)
    }

    @Test
    fun processKey_consumedWithoutCommit_renderContextOnly() = runTest {
        val context = testContext(input = "n", candidates = listOf("你" to "ni"))
        fakeEngine.api.bulkResults = mutableListOf(
            KeyEventResult(consumed = true, commit = null, context = context)
        )

        controller.processKey('n'.code, 0)

        verify(exactly = 0) { inputConnection.commitText(any(), any()) }
        assertEquals(context, callbacks.lastRenderedContext)
    }

    @Test
    fun processKey_consumedWithNullCommitText_doesNotCommit() = runTest {
        fakeEngine.api.bulkResults = mutableListOf(
            KeyEventResult(consumed = true, commit = CommitProto(null), context = null)
        )

        controller.processKey('n'.code, 0)

        verify(exactly = 0) { inputConnection.commitText(any(), any()) }
        assertNull(callbacks.lastRenderedContext)
    }

    // ===== 按键未消费：退格 =====

    @Test
    fun processKey_notConsumedBackspace_deletesSurroundingText() = runTest {
        fakeEngine.api.bulkResults = mutableListOf(
            KeyEventResult(consumed = false, commit = null, context = null)
        )

        controller.processKey(KeyCode.XK_BackSpace, 0)

        verify { inputConnection.deleteSurroundingText(1, 0) }
    }

    @Test
    fun processKey_notConsumedBackspaceWithCommitTarget_routesToTarget() = runTest {
        fakeEngine.api.bulkResults = mutableListOf(
            KeyEventResult(consumed = false, commit = null, context = null)
        )
        val target = TestCommitTarget()
        controller.commitTarget = target

        controller.processKey(KeyCode.XK_BackSpace, 0)

        assertEquals(1, target.deleteBackwardCalls)
        verify(exactly = 0) { inputConnection.deleteSurroundingText(any(), any()) }
    }

    // ===== 按键未消费：回车 =====

    @Test
    fun processKey_notConsumedReturnWithCommitTarget_callsTargetOnEnter() = runTest {
        fakeEngine.api.bulkResults = mutableListOf(
            KeyEventResult(consumed = false, commit = null, context = null)
        )
        val target = TestCommitTarget()
        controller.commitTarget = target

        controller.processKey(KeyCode.XK_Return, 0)

        assertEquals(1, target.onEnterCalls)
    }

    @Test
    fun processKey_notConsumedReturnWithoutCommitTarget_sendsEnterKeyEvents() = runTest {
        fakeEngine.api.bulkResults = mutableListOf(
            KeyEventResult(consumed = false, commit = null, context = null)
        )

        controller.processKey(KeyCode.XK_Return, 0)

        // 无编辑器动作（editorInfo 为空）→ 补发一对 ENTER 按下/抬起事件插入换行
        verify(exactly = 2) { inputConnection.sendKeyEvent(any()) }
        verify(exactly = 0) { inputConnection.performEditorAction(any()) }
        verify(exactly = 0) { inputConnection.commitText(any(), any()) }
    }

    @Test
    fun processKey_notConsumedReturnWithEditorAction_performsEditorAction() = runTest {
        fakeEngine.api.bulkResults = mutableListOf(
            KeyEventResult(consumed = false, commit = null, context = null)
        )
        callbacks.editorInfo = EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_SEARCH }

        controller.processKey(KeyCode.XK_Return, 0)

        // 单行搜索框：回车执行编辑器动作而非插入换行符
        verify { inputConnection.performEditorAction(EditorInfo.IME_ACTION_SEARCH) }
        verify(exactly = 0) { inputConnection.sendKeyEvent(any()) }
    }

    @Test
    fun processKey_notConsumedReturnMultiLineEditor_sendsEnterKeyEvents() = runTest {
        fakeEngine.api.bulkResults = mutableListOf(
            KeyEventResult(consumed = false, commit = null, context = null)
        )
        // 多行输入框即使声明了 action，回车仍为换行
        callbacks.editorInfo = EditorInfo().apply {
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        controller.processKey(KeyCode.XK_Return, 0)

        verify(exactly = 2) { inputConnection.sendKeyEvent(any()) }
        verify(exactly = 0) { inputConnection.performEditorAction(any()) }
    }

    // ===== 按键未消费：可打印字符 =====

    @Test
    fun processKey_notConsumedPrintableChar_commitsChar() = runTest {
        fakeEngine.api.bulkResults = mutableListOf(
            KeyEventResult(consumed = false, commit = null, context = null)
        )

        controller.processKey('a'.code, 0)

        verify { inputConnection.commitText("a", 1) }
        assertEquals(listOf(1), commitListenerCalls)
    }

    @Test
    fun processKey_notConsumedPrintableCharWithMask_doesNotCommit() = runTest {
        fakeEngine.api.bulkResults = mutableListOf(
            KeyEventResult(consumed = false, commit = null, context = null)
        )

        controller.processKey('a'.code, KeyCode.kShiftMask)

        verify(exactly = 0) { inputConnection.commitText(any(), any()) }
    }

    @Test
    fun processKey_notConsumedSpace_commitsSpace() = runTest {
        fakeEngine.api.bulkResults = mutableListOf(
            KeyEventResult(consumed = false, commit = null, context = null)
        )

        controller.processKey(0x20, 0) // space

        verify { inputConnection.commitText(" ", 1) }
    }

    // ===== commitTarget 路由 =====

    @Test
    fun processKey_consumedWithCommitTarget_routesToTargetNotEditor() = runTest {
        fakeEngine.api.bulkResults = mutableListOf(
            KeyEventResult(consumed = true, commit = CommitProto("test"), context = null)
        )
        val target = TestCommitTarget()
        controller.commitTarget = target

        controller.processKey('t'.code, 0)

        assertEquals(listOf("test"), target.committedTexts)
        verify(exactly = 0) { inputConnection.commitText(any(), any()) }
        // 面板路径不回调 commitListeners
        assertTrue(commitListenerCalls.isEmpty())
    }

    // ===== 异常容错 =====

    @Test
    fun processKey_engineThrows_caughtNoCrash() = runTest {
        fakeEngine.api.processKeyBulkThrowable = RuntimeException("engine boom")

        controller.processKey('a'.code, 0)

        // 异常被 catch，没有 commit / render
        verify(exactly = 0) { inputConnection.commitText(any(), any()) }
        assertNull(callbacks.lastRenderedContext)
    }

    // ===== inputMutex 串行化 =====

    @Test
    fun processKey_concurrentCalls_executedSequentially() = runTest {
        // 第一调用返回 consumed=true + commit，第二调用返回 consumed=false
        fakeEngine.api.bulkResults = mutableListOf(
            KeyEventResult(consumed = true, commit = CommitProto("first"), context = null),
            KeyEventResult(consumed = false, commit = null, context = null)
        )

        val d1 = async { controller.processKey('a'.code, 0) }
        val d2 = async { controller.processKey('b'.code, 0) }
        awaitAll(d1, d2)

        // 两次调用按顺序记录
        assertEquals(2, fakeEngine.api.processKeyBulkCalls.size)
        assertEquals('a'.code, fakeEngine.api.processKeyBulkCalls[0].first)
        assertEquals('b'.code, fakeEngine.api.processKeyBulkCalls[1].first)
        // 第一次 commit "first"，第二次未消费不 commit
        verify(exactly = 1) { inputConnection.commitText("first", 1) }
    }

    // ===== selectCandidate =====

    @Test
    fun selectCandidate_success_commitsAndUpdates() = runTest {
        fakeEngine.api.selectCandidateResult = true
        fakeEngine.api.nextCommit = CommitProto("你好")
        fakeEngine.api.nextContext = testContext(input = "", candidates = emptyList())
        keyRecordStack.pushT9Key('6')

        controller.selectCandidate(0)

        // selectCandidate 启动协程，需让协程执行
        // 由于使用 Unconfined dispatcher，协程已同步执行
        verify { inputConnection.commitText("你好", 1) }
        assertTrue(keyRecordStack.isEmpty())
        assertEquals(listOf(2), commitListenerCalls)
    }

    @Test
    fun selectCandidate_partialConfirm_syncsStackWithoutCommit() = runTest {
        // nihao 击键 64426，选“你”仅覆盖前缀：引擎分段确认、无 commit
        fakeEngine.api.selectCandidateResult = true
        fakeEngine.api.nextCommit = null
        fakeEngine.api.nextContext = testContext(
            input = "64426",
            candidates = listOf("你" to "ni", "尼" to "ni")
        )
        "64426".forEach { keyRecordStack.pushT9Key(it) }

        controller.selectCandidate(0)

        // 无上屏；状态机同步确认段（ni 占 2 个原始键），后续操作针对未确认段
        verify(exactly = 0) { inputConnection.commitText(any(), any()) }
        assertTrue(keyRecordStack.hasConfirmed())
        assertEquals(2, keyRecordStack.confirmedRawLength())
        assertEquals("426", keyRecordStack.unconfirmedRawChars())
        // 分段确认后补发 End：给已选段打编辑标记，使后续退格恒为删字语义
        // （否则首个退格会 ReopenPreviousSelection 撤销选择，造成栈-引擎失配）
        assertEquals(listOf(KeyCode.XK_End to 0), fakeEngine.api.processKeyBulkCalls)
        // UI 已按最新上下文刷新
        assertEquals(fakeEngine.api.nextContext, callbacks.lastRenderedContext)
    }

    @Test
    fun selectCandidate_partialConfirmCommentMismatch_clearsStackDegrade() = runTest {
        // 注音与击键不匹配（ma=62 vs 首键 4）→ 清栈降级，宁可降级不可错位
        fakeEngine.api.selectCandidateResult = true
        fakeEngine.api.nextCommit = null
        fakeEngine.api.nextContext = testContext(input = "486", candidates = listOf("妈" to "ma"))
        "486".forEach { keyRecordStack.pushT9Key(it) }

        controller.selectCandidate(0)

        assertTrue(keyRecordStack.isEmpty())
    }

    @Test
    fun selectCandidate_globalIndex_selectsWithGlobalFlag() = runTest {
        // 视图传来全局索引，应以 global=true 原样传给引擎（无二次转换）
        fakeEngine.api.selectCandidateResult = true
        fakeEngine.api.nextCommit = CommitProto("他")
        fakeEngine.api.nextContext = testContext(input = "", candidates = emptyList())
        keyRecordStack.pushT9Key('8')

        controller.selectCandidate(2)

        assertEquals(2, fakeEngine.api.selectCandidateCalls.last().first)
        assertTrue(fakeEngine.api.selectCandidateCalls.last().second)
    }

    @Test
    fun selectCandidate_crossPageGlobalIndex_noDoubleConversion() = runTest {
        // 复现旧缺陷：翻到第 1 页(pageSize=5)后点击，全局索引 7 必须原样传给引擎，
        // 不能再被二次换算成页内局部索引（旧实现会错位成 0）。
        fakeEngine.api.selectCandidateResult = true
        fakeEngine.api.nextCommit = CommitProto("地")
        fakeEngine.api.nextContext = testContext(
            input = "",
            candidates = listOf("大" to "da", "地" to "di", "的" to "de", "大" to "da", "得" to "de"),
            pageNumber = 1,
            pageSize = 5
        )
        keyRecordStack.pushT9Key('3')

        controller.selectCandidate(7)

        // 全局索引 7 原样传递 + global=true
        assertEquals(7, fakeEngine.api.selectCandidateCalls.last().first)
        assertTrue(fakeEngine.api.selectCandidateCalls.last().second)
    }

    @Test
    fun selectCandidate_crossPageSelected_commitsAndClearsStack() = runTest {
        // 跨页选中（全局索引落在旧页，menu.candidates 取不到 comment）：
        // 仍应正常上屏并清栈，不因 selected=null 崩溃。
        fakeEngine.api.selectCandidateResult = true
        fakeEngine.api.nextCommit = CommitProto("好")
        fakeEngine.api.nextContext = testContext(
            input = "",
            candidates = listOf("你" to "ni"),
            pageNumber = 1,
            pageSize = 5
        )
        keyRecordStack.pushT9Key('4')

        // 全局索引 2 落在第 0 页（引擎当前在第 1 页），selected=null 降级
        controller.selectCandidate(2)

        verify { inputConnection.commitText("好", 1) }
        assertTrue(keyRecordStack.isEmpty())
        assertEquals(2, fakeEngine.api.selectCandidateCalls.last().first)
        assertTrue(fakeEngine.api.selectCandidateCalls.last().second)
    }

    // ===== retypeUnconfirmed（存在确认段时的编码更新路径）=====

    @Test
    fun deleteUnconfirmedBackward_usesEndLeftDeleteSequence() = runTest {
        fakeEngine.api.nextContext = testContext(input = "64648426", candidates = emptyList())

        controller.deleteUnconfirmedBackward()

        // 无 Reopen 安全删除序列：End 归位 → KP_Left 左移一格 → Delete 前向删；
        // 不用 BackSpace（删空未确认键时会 Reopen 已确认段）
        assertEquals(
            listOf(KeyCode.XK_End to 0, KeyCode.XK_KP_Left to 0, KeyCode.XK_Delete to 0),
            fakeEngine.api.processKeyBulkCalls
        )
        // 末尾刷新了 UI
        assertEquals(fakeEngine.api.nextContext, callbacks.lastRenderedContext)
    }

    @Test
    fun retypeUnconfirmed_movesCaretDeletesForwardThenRetypes() = runTest {
        controller.retypeUnconfirmed(3, "hao'")

        // 序列：End 归位 → KP_Left×3 移到确认边界 → Delete×3 前向删未确认键
        // → 逐键重打 hao'。不用 BackSpace（删到确认边界会 Reopen 已确认段），
        // 也不走 replaceKey（set_input 会清空确认段）
        val calls = fakeEngine.api.processKeyBulkCalls
        assertEquals(11, calls.size)
        assertEquals(KeyCode.XK_End, calls[0].first)
        repeat(3) { assertEquals(KeyCode.XK_KP_Left, calls[it + 1].first) }
        repeat(3) { assertEquals(KeyCode.XK_Delete, calls[it + 4].first) }
        assertEquals(
            listOf('h'.code, 'a'.code, 'o'.code, '\''.code),
            calls.drop(7).map { it.first }
        )
        assertTrue(calls.none { it.first == KeyCode.XK_BackSpace })
        assertTrue(fakeEngine.api.replaceKeyCalls.isEmpty())
    }

    // ===== 编码长度上限防护 =====

    @Test
    fun processKey_inputAtLimit_composingKeyDroppedAfterRealtimeConfirm() = runTest {
        val longInput = "2".repeat(InputLogicController.MAX_INPUT_LENGTH)
        // 第一键：引擎消费，编码长度达上限（缓存更新）
        fakeEngine.api.bulkResults = mutableListOf(
            KeyEventResult(consumed = true, commit = null, context = testContext(longInput))
        )
        controller.processKey('2'.code, 0)
        assertEquals(1, fakeEngine.api.processKeyBulkCalls.size)

        // 实时确认仍超限 → 第二个编码键被丢弃（不再送引擎）
        fakeEngine.api.nextContext = testContext(longInput)
        val contextCallsBefore = fakeEngine.api.contextCalls
        controller.processKey('3'.code, 0)

        assertEquals(1, fakeEngine.api.processKeyBulkCalls.size)
        assertEquals(contextCallsBefore + 1, fakeEngine.api.contextCalls)
    }

    @Test
    fun processKey_staleLimitCache_refreshedByRealtimeCheckAndKeyAccepted() = runTest {
        val longInput = "2".repeat(InputLogicController.MAX_INPUT_LENGTH)
        fakeEngine.api.bulkResults = mutableListOf(
            KeyEventResult(consumed = true, commit = null, context = testContext(longInput)),
            KeyEventResult(consumed = true, commit = null, context = testContext("3"))
        )
        controller.processKey('2'.code, 0)

        // 旁路（如选词/清编码）已清空编码 → 实时确认后新编码键正常放行
        fakeEngine.api.nextContext = testContext("")
        controller.processKey('3'.code, 0)

        assertEquals(2, fakeEngine.api.processKeyBulkCalls.size)
    }

    @Test
    fun processKey_atLimit_functionKeysBypassGuard() = runTest {
        val longInput = "2".repeat(InputLogicController.MAX_INPUT_LENGTH)
        fakeEngine.api.bulkResults = mutableListOf(
            KeyEventResult(consumed = true, commit = null, context = testContext(longInput)),
            KeyEventResult(consumed = true, commit = null, context = testContext(longInput)),
            KeyEventResult(consumed = true, commit = null, context = testContext(longInput))
        )
        controller.processKey('2'.code, 0)
        val contextCallsBefore = fakeEngine.api.contextCalls

        // 退格与空格（T9 选首候选）不受限：直接送引擎，无实时确认开销
        controller.processKey(KeyCode.XK_BackSpace, 0)
        controller.processKey(' '.code, 0)

        assertEquals(3, fakeEngine.api.processKeyBulkCalls.size)
        assertEquals(contextCallsBefore, fakeEngine.api.contextCalls)
    }

    // ===== 辅助 =====

    private fun testContext(
        input: String,
        candidates: List<Pair<String, String>> = emptyList(),
        pageNumber: Int = 0,
        pageSize: Int = -1
    ): ContextProto {
        val effectivePageSize = if (pageSize >= 0) pageSize else candidates.size
        val menu = if (candidates.isEmpty() && pageSize < 0) null else MenuProto(
            pageSize = effectivePageSize.coerceAtLeast(1),
            pageNumber = pageNumber,
            isLastPage = true,
            highlightedCandidateIndex = 0,
            candidates = candidates.map { (text, comment) ->
                com.ziyou.ime.core.CandidateProto(text, comment, "")
            }.toTypedArray(),
            selectKeys = "",
            selectLabels = emptyArray()
        )
        return ContextProto(
            composition = CompositionProto(
                length = input.length,
                cursorPos = input.length,
                selStart = 0,
                selEnd = input.length,
                preedit = input,
                commitTextPreview = null
            ),
            menu = menu,
            input = input,
            caretPos = input.length
        )
    }

    /** 记录渲染上下文与 InputConnection 的测试 Callbacks */
    private class TestCallbacks(
        private val ic: InputConnection
    ) : InputLogicController.Callbacks {
        var lastRenderedContext: ContextProto? = null
            private set

        /** 当前编辑器信息（换行键语义取决于 imeOptions/inputType） */
        var editorInfo: EditorInfo? = null

        override fun currentInputConnection(): InputConnection? = ic
        override fun currentEditorInfo(): EditorInfo? = editorInfo
        override fun renderContext(context: ContextProto?) {
            lastRenderedContext = context
        }
    }

    /** 记录 commit / deleteBackward / onEnter 的测试 CommitTarget */
    private class TestCommitTarget : InputLogicController.CommitTarget {
        val committedTexts = mutableListOf<CharSequence>()
        var deleteBackwardCalls = 0
        var onEnterCalls = 0

        override fun commit(text: CharSequence) {
            committedTexts.add(text)
        }

        override fun deleteBackward() {
            deleteBackwardCalls++
        }

        override fun onEnter() {
            onEnterCalls++
        }
    }
}
