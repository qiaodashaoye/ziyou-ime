package com.ziyou.ime.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [RimeDispatcher] 调度路径测试。
 *
 * 使用 [runBlocking]（而非 `runTest`）避免虚拟时间与真实单线程调度器的冲突。
 *
 * 覆盖：
 * - dispatch 在专属线程执行（非调用线程）
 * - dispatch 异常传播
 * - dispatchWithTimeout 超时返回 null
 * - dispatchWithTimeout 正常完成返回结果
 * - shutdown 后 dispatch 抛出异常
 * - shutdown 后 dispatchWithTimeout 返回 null
 * - shutdown 幂等
 */
class RimeDispatcherTest {

    @Test
    fun dispatch_executesOnDedicatedThread() = runBlocking {
        val dispatcher = RimeDispatcher()
        val callerThread = Thread.currentThread()
        val execThreadRef = AtomicReference<Thread>(null)

        dispatcher.dispatch {
            execThreadRef.set(Thread.currentThread())
        }

        val execThread = execThreadRef.get()
        assertNotNull(execThread)
        assertTrue("应在 RimeDispatcher-Thread 执行，而非调用线程",
            execThread != callerThread)
        assertTrue("线程名应以 RimeDispatcher 开头",
            execThread!!.name.startsWith("RimeDispatcher"))

        dispatcher.shutdown()
    }

    @Test
    fun dispatch_returnsResult() = runBlocking {
        val dispatcher = RimeDispatcher()

        val result = dispatcher.dispatch { 42 }

        assertEquals(42, result)
        dispatcher.shutdown()
    }

    @Test
    fun dispatch_propagatesException() = runBlocking {
        val dispatcher = RimeDispatcher()

        val exception = runCatching {
            dispatcher.dispatch { throw RuntimeException("test error") }
        }.exceptionOrNull()

        assertNotNull(exception)
        assertEquals("test error", exception!!.message)
        dispatcher.shutdown()
    }

    @Test
    fun dispatchWithTimeout_returnsResultOnTime() = runBlocking {
        val dispatcher = RimeDispatcher()

        val result = dispatcher.dispatchWithTimeout(timeoutMs = 5000) { "success" }

        assertEquals("success", result)
        dispatcher.shutdown()
    }

    @Test
    fun dispatchWithTimeout_returnsNullOnTimeout() = runBlocking {
        val dispatcher = RimeDispatcher()
        val latch = CountDownLatch(1)

        // 模拟阻塞操作：用 latch 阻塞 Rime 线程，使其无法在超时内完成
        val result = dispatcher.dispatchWithTimeout(timeoutMs = 200) {
            latch.await(10, TimeUnit.SECONDS) // 阻塞 10 秒
            "should not reach"
        }

        assertNull("超时应返回 null", result)
        // 释放 latch 让 Rime 线程可以退出
        latch.countDown()
        dispatcher.shutdown()
    }

    @Test
    fun dispatch_throwsAfterShutdown() = runBlocking {
        val dispatcher = RimeDispatcher()
        dispatcher.shutdown()

        val exception = runCatching {
            dispatcher.dispatch { "no-op" }
        }.exceptionOrNull()

        assertNotNull(exception)
        assertTrue(exception is IllegalStateException)
        assertTrue(exception!!.message!!.contains("已关闭"))
    }

    @Test
    fun dispatchWithTimeout_returnsNullAfterShutdown() = runBlocking {
        val dispatcher = RimeDispatcher()
        dispatcher.shutdown()

        val result = dispatcher.dispatchWithTimeout { "no-op" }

        assertNull(result)
    }

    @Test
    fun shutdown_isIdempotent() = runBlocking {
        val dispatcher = RimeDispatcher()

        // 多次 shutdown 不抛异常
        dispatcher.shutdown()
        dispatcher.shutdown()
        dispatcher.shutdown()
    }
}
