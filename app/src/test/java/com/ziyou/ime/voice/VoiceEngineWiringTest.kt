package com.ziyou.ime.voice

import com.ziyou.ime.core.voice.VoiceUtteranceBuffer
import com.ziyou.ime.di.AppContainer
import com.ziyou.ime.testing.FakeSpeechRecognizerEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 语音引擎装配与识别→上屏数据流测试。
 *
 * 经 [AppContainer.overrideSpeechEngine] 注入 [FakeSpeechRecognizerEngine]（不加载 native 库），
 * 覆盖 DI 装配契约与「partial 只预览 / final 才上屏」的调用纪律。
 */
class VoiceEngineWiringTest {

    @After
    fun tearDown() {
        AppContainer.overrideSpeechEngine(null)
    }

    // ===== DI 装配 =====

    @Test
    fun `默认语音引擎为 SherpaOnnxEngine 且构造不触发模型加载`() {
        // SherpaOnnxEngine 构造只做字段初始化，JNI 库与模型都在 loadModel 才加载，
        // 因此纯 JVM 单测中安全构造
        val engine = AppContainer.speechEngine
        assertNotNull(engine)
        assertTrue(engine is SherpaOnnxEngine)
        assertFalse(engine.isModelLoaded)
    }

    @Test
    fun `override 注入与还原`() {
        val fake = FakeSpeechRecognizerEngine()
        AppContainer.overrideSpeechEngine(fake)
        assertSame(fake, AppContainer.speechEngine)
        AppContainer.overrideSpeechEngine(null)
        assertTrue(AppContainer.speechEngine is SherpaOnnxEngine)
    }

    @Test
    fun `release 后 speechEngine 返回全新可用实例`() {
        // 回归：Service onDestroy 会 release 引擎归还 native 内存，但进程可能存活且
        // Service 随后重建——已释放实例的 destroyed 是单向闩锁，若懒获取仍返回
        // 旧实例，loadModel 将永远报「引擎已释放」，语音功能直到杀进程才恢复
        val first = AppContainer.speechEngine
        assertFalse(first.isReleased)
        first.release()
        assertTrue(first.isReleased)

        val second = AppContainer.speechEngine
        assertNotSame(first, second)
        assertTrue(second is SherpaOnnxEngine)
        assertFalse(second.isReleased)
        assertFalse(second.isModelLoaded)
        // 清理：避免已释放实例残留在容器内影响后续用例（下次访问会自动重建）
        second.release()
    }

    @Test
    fun `override 实例不受 release 重建逻辑影响`() {
        val fake = FakeSpeechRecognizerEngine()
        AppContainer.overrideSpeechEngine(fake)
        fake.release()
        // override 是显式注入，容器不得擅自动替换（即使已释放也由测试自己还原）
        assertSame(fake, AppContainer.speechEngine)
        assertTrue(fake.isReleased)
    }

    @Test
    fun `fake 引擎的加载与会话生命周期`() {
        val fake = FakeSpeechRecognizerEngine()
        assertFalse(fake.isModelLoaded)

        assertNull(fake.loadModel(File("model-dir")))
        assertTrue(fake.isModelLoaded)

        val listener = RecordingListener()
        assertNull(fake.startSession(listener))
        assertEquals(1, fake.startCount)

        fake.emitPartial("今天")
        fake.emitFinal("今天天气不错")
        assertEquals(listOf("今天"), listener.partials)
        assertEquals(listOf("今天天气不错"), listener.finals)

        fake.stopSession()
        assertEquals(1, fake.stopCount)
        assertNull(fake.sessionListener)
    }

    // ===== 识别→缓冲→上屏增量 数据流纪律 =====

    @Test
    fun `partial 只进预览，final 经 drain 产出上屏增量`() {
        val fake = FakeSpeechRecognizerEngine()
        AppContainer.overrideSpeechEngine(fake)
        val buffer = VoiceUtteranceBuffer()

        val listener = object : SpeechRecognizerEngine.Listener {
            override fun onPartial(text: String) {
                buffer.updatePartial(text)
            }

            override fun onFinal(text: String) {
                buffer.commitSegment(text)
            }

            override fun onError(message: String) = Unit
        }
        assertNull(AppContainer.speechEngine.startSession(listener))

        // 模拟流式识别：partial 反复改写，最终端点确认
        fake.emitPartial("今天天")
        fake.emitPartial("今天天气")   // 识别纠错改写，不上屏
        assertEquals("今天天气", buffer.preview())
        assertEquals("", buffer.drainConfirmed())

        fake.emitFinal("今天天气不错")
        assertEquals("今天天气不错", buffer.drainConfirmed())
        assertEquals("", buffer.drainConfirmed()) // drain 语义：不重复投递

        // 第二句续说
        fake.emitPartial("明天")
        fake.emitFinal("明天有雨")
        assertEquals("今天天气不错明天有雨", buffer.preview())
        assertEquals("明天有雨", buffer.drainConfirmed())

        fake.stopSession()
    }

    // ===== 模型切换契约（修复「切换激活模型后仍用旧模型识别」缺陷的回归测试）=====

    @Test
    fun `isModelLoadedFor 仅对已加载目录为真`() {
        val fake = FakeSpeechRecognizerEngine()
        val dirA = File("voice-models/model-a")
        val dirB = File("voice-models/model-b")

        assertFalse(fake.isModelLoadedFor(dirA))
        assertNull(fake.loadModel(dirA))
        // 已加载 A：对 A 为真、对 B 为假——协调器据此触发重加载
        assertTrue(fake.isModelLoadedFor(dirA))
        assertFalse(fake.isModelLoadedFor(dirB))

        // 切换到 B：对 B 为真、对 A 不再为真
        assertNull(fake.loadModel(dirB))
        assertTrue(fake.isModelLoadedFor(dirB))
        assertFalse(fake.isModelLoadedFor(dirA))
    }

    @Test
    fun `加载失败后不保留旧模型的就绪假象`() {
        val fake = FakeSpeechRecognizerEngine()
        assertNull(fake.loadModel(File("model-a")))
        fake.loadModelError = "模拟加载失败"
        assertNotNull(fake.loadModel(File("model-b")))
        // 失败后 isModelLoadedFor 对任何目录都不应为真，强制下次重新加载
        assertFalse(fake.isModelLoadedFor(File("model-a")))
        assertFalse(fake.isModelLoadedFor(File("model-b")))
    }

    /** 记录回调的测试监听器。 */
    private class RecordingListener : SpeechRecognizerEngine.Listener {
        val partials = mutableListOf<String>()
        val finals = mutableListOf<String>()
        val errors = mutableListOf<String>()

        override fun onPartial(text: String) {
            partials += text
        }

        override fun onFinal(text: String) {
            finals += text
        }

        override fun onError(message: String) {
            errors += message
        }
    }
}
