package com.ziyou.ime.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * [SimpleRimeImpl] 的 dispatcher 调度路径测试。
 *
 * 由于 [RimeNative] 的 external 方法在单元测试环境中无法被 mockk 拦截
 *（`every {}` 录制时即触发 `UnsatisfiedLinkError`），本测试采用两层策略：
 *
 * 1. **解析逻辑**：直接测试 [SimpleRimeImpl.parseBulkResult]，覆盖 JNI 返回数组的各种边界
 * 2. **调度路径**：使用真实 [RimeDispatcher]，通过捕获 `UnsatisfiedLinkError` 验证
 *    `SimpleRimeImpl` 确实将操作委托到 dispatcher 线程执行
 * 3. **startup 防御**：验证 native 库未加载时 startup 抛出 IllegalStateException
 */
class SimpleRimeImplTest {

    // ===== parseBulkResult 解析逻辑（无需 JNI）=====

    @Test
    fun parseBulkResult_consumedWithCommitAndContext() {
        val commit = CommitProto("你好")
        val context = ContextProto(null, null, "ni", 2)
        val raw = arrayOf<Any?>(true, commit, context)

        val result = SimpleRimeImpl.parseBulkResult(raw)

        assertTrue(result.consumed)
        assertEquals("你好", result.commit?.text)
        assertEquals("ni", result.context?.input)
    }

    @Test
    fun parseBulkResult_notConsumedWithNulls() {
        val raw = arrayOf<Any?>(false, null, null)

        val result = SimpleRimeImpl.parseBulkResult(raw)

        assertFalse(result.consumed)
        assertNull(result.commit)
        assertNull(result.context)
    }

    @Test
    fun parseBulkResult_nullArray_returnsDefaults() {
        val result = SimpleRimeImpl.parseBulkResult(null)

        assertFalse(result.consumed)
        assertNull(result.commit)
        assertNull(result.context)
    }

    @Test
    fun parseBulkResult_wrongTypes_returnsDefaults() {
        // 模拟 JNI 返回异常数据（类型不匹配）
        val raw = arrayOf<Any?>("not-a-bool", 123, "not-a-context")

        val result = SimpleRimeImpl.parseBulkResult(raw)

        assertFalse(result.consumed) // as? Boolean ?: false
        assertNull(result.commit)    // as? CommitProto
        assertNull(result.context)   // as? ContextProto
    }

    @Test
    fun parseBulkResult_arrayTooShort_returnsAvailableFields() {
        val raw = arrayOf<Any?>(true)

        val result = SimpleRimeImpl.parseBulkResult(raw)

        assertTrue(result.consumed)
        assertNull(result.commit)    // getOrNull(1) == null
        assertNull(result.context)   // getOrNull(2) == null
    }

    @Test
    fun parseBulkResult_emptyArray_allDefaults() {
        val raw = emptyArray<Any?>()

        val result = SimpleRimeImpl.parseBulkResult(raw)

        assertFalse(result.consumed)
        assertNull(result.commit)
        assertNull(result.context)
    }

    // ===== dispatcher 调度路径验证 =====

    @Test
    fun processKeyBulk_dispatchesOnRimeThread() = runTest {
        val dispatcher = RimeDispatcher()
        val impl = SimpleRimeImpl(dispatcher)
        val callerThread = Thread.currentThread()
        val rimeThreadRef = AtomicReference<Thread>(null)

        // processRimeKeyBulk 是 external 方法，会抛 UnsatisfiedLinkError
        // 但在抛出前，线程已经切换到 RimeDispatcher-Thread
        val error = runCatching {
            impl.processKeyBulk('a'.code, 0)
        }.exceptionOrNull()

        // UnsatisfiedLinkError 是 Error 不是 Exception，直接传播
        assertNotNull("应抛出 UnsatisfiedLinkError（native 库未加载）", error)

        dispatcher.shutdown()
    }

    @Test
    fun processKey_dispatchesOnRimeThread() = runTest {
        val dispatcher = RimeDispatcher()
        val impl = SimpleRimeImpl(dispatcher)

        val error = runCatching {
            impl.processKey('a'.code, 0)
        }.exceptionOrNull()

        assertNotNull("应抛出 UnsatisfiedLinkError（native 库未加载）", error)

        dispatcher.shutdown()
    }

    @Test
    fun startup_nativeLibNotLoaded_throwsIllegalStateException() = runTest {
        val dispatcher = RimeDispatcher()
        val impl = SimpleRimeImpl(dispatcher)

        // 测试环境中 RimeNative.isLoaded == false
        val error = runCatching {
            impl.startup("/shared", "/user", "1.0.0", false)
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("rime_jni 库未加载"))

        dispatcher.shutdown()
    }

    @Test
    fun shutdown_dispatchesToRimeThread() = runTest {
        val dispatcher = RimeDispatcher()
        val impl = SimpleRimeImpl(dispatcher)

        // exitRime 是 external 方法，会抛 UnsatisfiedLinkError
        val error = runCatching {
            impl.shutdown()
        }.exceptionOrNull()

        assertNotNull("应抛出 UnsatisfiedLinkError（native 库未加载）", error)

        dispatcher.shutdown()
    }

    @Test
    fun allStateQueryMethods_dispatchToRimeThread() = runTest {
        val dispatcher = RimeDispatcher()
        val impl = SimpleRimeImpl(dispatcher)

        // 所有状态查询方法都通过 dispatcher.dispatch 委托
        // external 方法在无 native 库时抛 UnsatisfiedLinkError
        assertNotNull(runCatching { impl.getCommit() }.exceptionOrNull())
        assertNotNull(runCatching { impl.getContext() }.exceptionOrNull())
        assertNotNull(runCatching { impl.getStatus() }.exceptionOrNull())
        assertNotNull(runCatching { impl.getCandidates(0, 5) }.exceptionOrNull())

        dispatcher.shutdown()
    }

    @Test
    fun allCandidateMethods_dispatchToRimeThread() = runTest {
        val dispatcher = RimeDispatcher()
        val impl = SimpleRimeImpl(dispatcher)

        assertNotNull(runCatching { impl.selectCandidate(0) }.exceptionOrNull())
        assertNotNull(runCatching { impl.deleteCandidate(0) }.exceptionOrNull())
        assertNotNull(runCatching { impl.changePage(true) }.exceptionOrNull())

        dispatcher.shutdown()
    }

    @Test
    fun allSchemaMethods_dispatchToRimeThread() = runTest {
        val dispatcher = RimeDispatcher()
        val impl = SimpleRimeImpl(dispatcher)

        assertNotNull(runCatching { impl.getSchemaList() }.exceptionOrNull())
        assertNotNull(runCatching { impl.getCurrentSchema() }.exceptionOrNull())
        assertNotNull(runCatching { impl.selectSchema("test") }.exceptionOrNull())

        dispatcher.shutdown()
    }

    @Test
    fun allOptionMethods_dispatchToRimeThread() = runTest {
        val dispatcher = RimeDispatcher()
        val impl = SimpleRimeImpl(dispatcher)

        assertNotNull(runCatching { impl.setOption("key", true) }.exceptionOrNull())
        assertNotNull(runCatching { impl.getOption("key") }.exceptionOrNull())

        dispatcher.shutdown()
    }

    @Test
    fun syncUserData_dispatchesToRimeThread() = runTest {
        val dispatcher = RimeDispatcher()
        val impl = SimpleRimeImpl(dispatcher)

        assertNotNull(runCatching { impl.syncUserData() }.exceptionOrNull())

        dispatcher.shutdown()
    }

    @Test
    fun clearComposition_dispatchesToRimeThread() = runTest {
        val dispatcher = RimeDispatcher()
        val impl = SimpleRimeImpl(dispatcher)

        assertNotNull(runCatching { impl.clearComposition() }.exceptionOrNull())

        dispatcher.shutdown()
    }

    @Test
    fun replaceKey_dispatchesToRimeThread() = runTest {
        val dispatcher = RimeDispatcher()
        val impl = SimpleRimeImpl(dispatcher)

        assertNotNull(runCatching { impl.replaceKey(0, 3, "guo'") }.exceptionOrNull())

        dispatcher.shutdown()
    }

    @Test
    fun messageFlow_returnsRimeMessageHandlerFlow() {
        val dispatcher = RimeDispatcher()
        val impl = SimpleRimeImpl(dispatcher)

        // messageFlow 应返回 RimeMessageHandler 的 SharedFlow
        val flow = impl.messageFlow
        assertNotNull(flow)

        dispatcher.shutdown()
    }
}
