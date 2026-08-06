package com.ziyou.ime.testing

import com.ziyou.ime.voice.SpeechRecognizerEngine
import java.io.File

/**
 * [SpeechRecognizerEngine] 测试替身（对齐 [FakeRimeEngine] 惯例）：
 * 不加载任何 native 库，识别结果由测试经 emit* 手动驱动。
 */
class FakeSpeechRecognizerEngine : SpeechRecognizerEngine {

    /** loadModel 应返回的失败原因（null = 成功）。 */
    var loadModelError: String? = null

    /** startSession 应返回的失败原因（null = 成功）。 */
    var startSessionError: String? = null

    var loadedModelDir: File? = null
        private set

    var sessionListener: SpeechRecognizerEngine.Listener? = null
        private set

    var startCount = 0
        private set

    var stopCount = 0
        private set

    var released = false
        private set

    override val isModelLoaded: Boolean get() = loadedModelDir != null && loadModelError == null

    override val isReleased: Boolean get() = released

    override fun isModelLoadedFor(modelDir: File): Boolean =
        isModelLoaded && loadedModelDir?.path == modelDir.path

    override fun loadModel(modelDir: File): String? {
        loadModelError?.let { return it }
        loadedModelDir = modelDir
        return null
    }

    override fun startSession(listener: SpeechRecognizerEngine.Listener): String? {
        startSessionError?.let { return it }
        sessionListener = listener
        startCount++
        return null
    }

    override fun stopSession() {
        stopCount++
        sessionListener = null
    }

    override fun release() {
        released = true
        sessionListener = null
        loadedModelDir = null
    }

    // ===== 测试驱动入口 =====

    fun emitPartial(text: String) = sessionListener?.onPartial(text) ?: Unit

    fun emitFinal(text: String) = sessionListener?.onFinal(text) ?: Unit

    fun emitError(message: String) = sessionListener?.onError(message) ?: Unit
}
