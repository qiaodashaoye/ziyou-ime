package com.ziyou.ime.ime

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
    fun processKey_notConsumedReturnWithoutCommitTarget_noOp() = runTest {
        fakeEngine.api.bulkResults = mutableListOf(
            KeyEventResult(consumed = false, commit = null, context = null)
        )

        controller.processKey(KeyCode.XK_Return, 0)

        verify(exactly = 0) { inputConnection.commitText(any(), any()) }
        verify(exactly = 0) { inputConnection.deleteSurroundingText(any(), any()) }
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

    // ===== 辅助 =====

    private fun testContext(
        input: String,
        candidates: List<Pair<String, String>> = emptyList()
    ): ContextProto {
        val menu = if (candidates.isEmpty()) null else MenuProto(
            pageSize = candidates.size,
            pageNumber = 0,
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

        override fun currentInputConnection(): InputConnection? = ic
        override fun currentEditorInfo(): EditorInfo? = null
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
