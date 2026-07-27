package com.ziyou.ime.daemon

import android.content.Context
import com.ziyou.ime.core.RimeDispatcher
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * [RimeSession] 生命周期测试。
 *
 * 由于 [com.ziyou.ime.core.RimeNative] 的 external 方法在单元测试环境中无法被 mockk
 * 拦截（`every {}` 录制时即触发 `UnsatisfiedLinkError`），本测试聚焦于不依赖
 * native 库即可验证的生命周期行为：
 *
 * - 未初始化状态（api 抛异常、destroy 空操作）
 * - initialize 执行 deploySteps 后因 native 库缺失而失败
 * - 失败后 initialized 保持 false
 * - redeploy 重新执行 deploySteps
 *
 * **超时路径**：`RimeSession.initialize` 内部使用 `withTimeoutOrNull(120s)` 保护启动，
 * 其超时机制与 [com.ziyou.ime.core.RimeDispatcher.dispatchWithTimeout] 一致，
 * 后者的超时行为由 [com.ziyou.ime.core.RimeDispatcherTest] 覆盖。
 *
 * 注意：[RimeSession] 是 object 单例，测试间通过反射重置内部状态保证隔离。
 */
class RimeSessionLifecycleTest {

    @Before
    fun setUp() {
        resetRimeSessionState()
        RimeSession.deploySteps = emptyList()
    }

    @After
    fun tearDown() {
        resetRimeSessionState()
        RimeSession.deploySteps = emptyList()
    }

    // ===== 未初始化状态 =====

    @Test
    fun notInitialized_apiThrows() = runTest {
        assertFalse(RimeSession.initialized)
        val exception = runCatching { RimeSession.api }.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is IllegalStateException)
    }

    @Test
    fun notInitialized_destroyIsNoOp() = runTest {
        RimeSession.destroy()
        assertFalse(RimeSession.initialized)
    }

    // ===== 初始化失败（native 库不可用）=====

    @Test
    fun initialize_nativeLibNotLoaded_throwsAndStaysUninitialized() = runTest {
        // 测试环境中 RimeNative.isLoaded == false（无 native 库）
        RimeSession.deploySteps = emptyList()

        val exception = runCatching {
            RimeSession.initialize(mockContext(), false)
        }.exceptionOrNull()

        assertNotNull(exception)
        assertFalse(RimeSession.initialized)
    }

    // ===== deploySteps 执行 =====

    @Test
    fun initialize_executesDeployStepsBeforeFailure() = runTest {
        var stepExecuted = false
        RimeSession.deploySteps = listOf(RimeDeployStep { stepExecuted = true })

        // initialize 会因 native 库缺失而失败，但 deploySteps 在失败前已执行
        runCatching { RimeSession.initialize(mockContext(), false) }

        assertTrue("deploySteps 应在 startup 失败前执行", stepExecuted)
        assertFalse(RimeSession.initialized)
    }

    @Test
    fun initialize_executesMultipleDeployStepsInOrder() = runTest {
        val executionOrder = mutableListOf<Int>()
        RimeSession.deploySteps = listOf(
            RimeDeployStep { executionOrder.add(1) },
            RimeDeployStep { executionOrder.add(2) },
            RimeDeployStep { executionOrder.add(3) }
        )

        runCatching { RimeSession.initialize(mockContext(), false) }

        assertEquals(listOf(1, 2, 3), executionOrder)
    }

    // ===== 重新部署 =====

    @Test
    fun redeploy_reexecutesDeploySteps() = runTest {
        var stepCount = 0
        RimeSession.deploySteps = listOf(RimeDeployStep { stepCount++ })

        // redeploy 内部调用 doDestroy（空操作）+ doInitialize
        // doInitialize 执行 deploySteps 后因 native 库缺失失败
        runCatching { RimeSession.redeploy(mockContext()) }

        assertEquals("redeploy 应重新执行 deploySteps", 1, stepCount)
    }

    @Test
    fun redeploy_doesNotRequirePriorInit() = runTest {
        // 未初始化状态下调用 redeploy 应安全（doDestroy 空操作 + doInitialize）
        val exception = runCatching {
            RimeSession.redeploy(mockContext())
        }.exceptionOrNull()

        // 会因 native 库缺失而失败，但不是因为未初始化
        assertNotNull(exception)
        assertFalse(RimeSession.initialized)
    }

    // ===== 辅助方法 =====

    private fun mockContext(): Context {
        val context = mockk<Context>()
        val tempDir = Files.createTempDirectory("rime_test").toFile()
        every { context.filesDir } returns tempDir
        // 让 packageManager 抛异常，触发 versionName 回退到 "1.0.0"
        every { context.packageManager } throws SecurityException("test")
        return context
    }

    /**
     * 通过反射重置 [RimeSession] 单例内部状态，保证测试隔离。
     * destroy() 在 isInitialized=false 时是空操作，无法清理 initialize 失败时泄漏的
     * dispatcher/rimeApi/sessionScope，故需要直接反射重置。
     */
    private fun resetRimeSessionState() {
        val clazz = RimeSession::class.java

        clazz.getDeclaredField("dispatcher").apply {
            isAccessible = true
            (get(RimeSession) as? RimeDispatcher)?.shutdown()
            set(RimeSession, null)
        }
        clazz.getDeclaredField("rimeApi").apply {
            isAccessible = true
            set(RimeSession, null)
        }
        clazz.getDeclaredField("sessionScope").apply {
            isAccessible = true
            (get(RimeSession) as? CoroutineScope)?.cancel()
            set(RimeSession, null)
        }
        clazz.getDeclaredField("isInitialized").apply {
            isAccessible = true
            set(RimeSession, false)
        }
    }
}
