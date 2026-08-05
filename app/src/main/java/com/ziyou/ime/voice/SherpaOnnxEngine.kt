package com.ziyou.ime.voice

import android.util.Log
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.ziyou.ime.core.voice.VoiceTextCaseRestorer
import java.io.File

/**
 * sherpa-onnx 流式识别实现（选型依据见 docs/实时语音输入可行性方案.md）。
 *
 * 线程模型（与 RimeDispatcher 零交集，两条单线程互不竞争）：
 * - 音频采集与解码都在 AudioCapture 的专用线程上：acceptWaveform → decode → 回调
 *   Listener，sherpa 的 OnlineStream 天然只被该线程访问；
 * - [stopSession] 先 join 采集线程再做尾段冲刷与流释放，保证 stream 无并发访问。
 *
 * 端点策略交给识别引擎内置 EndpointConfig（默认尾部静默 1.4s/无语音 2.4s 断句），
 * 上层会话级自动收尾由 VoiceSessionStateMachine 负责（见 :core-logic）。
 *
 * 输出后处理：所有 partial/final 文本统一经 [VoiceTextCaseRestorer] 做英文
 * 大小写恢复——中英混合模型的英文 token 词表为大写，原始产出如 "I LOVE 中国"
 * 会恢复为 "I love 中国"；纯中文文本零改动。
 */
class SherpaOnnxEngine(
    private val numThreads: Int = DEFAULT_NUM_THREADS,
) : SpeechRecognizerEngine {

    companion object {
        private const val TAG = "SherpaOnnxEngine"

        /** 解码线程数：2 在中端机上可实时且不满大核，避免键盘 Canvas 绘制掉帧。 */
        const val DEFAULT_NUM_THREADS = 2
    }

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var capture: AudioCapture? = null
    private var listener: SpeechRecognizerEngine.Listener? = null
    private var lastPartial: String = ""

    /** recognizer 赋值/释放的串行化锁：loadModel 在 IO 线程阻塞执行，
     *  release 可能在主线程（Service onDestroy），两者对 recognizer 的读写经此互斥。 */
    private val stateLock = Any()

    /** release 后的守卫：迟到的 loadModel 写回前检查，避免已释放后模型「复活」常驻。 */
    @Volatile
    private var destroyed = false

    /** 当前已加载模型的目录（与 recognizer 同步维护）：切换激活模型时据此判定需重加载。 */
    private var loadedModelDir: File? = null

    @Volatile
    private var sessionActive = false

    override val isModelLoaded: Boolean get() = recognizer != null

    override fun isModelLoadedFor(modelDir: File): Boolean =
        recognizer != null && loadedModelDir?.absolutePath == modelDir.absolutePath

    override fun loadModel(modelDir: File): String? {
        if (!modelDir.isDirectory) return "模型目录不存在: ${modelDir.path}"
        val tokens = modelDir.resolve("tokens.txt")
        if (!tokens.isFile) return "缺少 tokens.txt"

        fun pick(prefix: String): File? = modelDir.listFiles()
            ?.firstOrNull { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".onnx") }

        val encoder = pick("encoder") ?: return "缺少 encoder*.onnx"
        val decoder = pick("decoder") ?: return "缺少 decoder*.onnx"
        val joiner = pick("joiner") ?: return "缺少 joiner*.onnx"

        // 重复加载前先卸载旧模型（不经 release()，避免误置 destroyed 守卫）；
        // 同步清空已加载目录，新模型加载失败时引擎回到「未加载」状态而非伪装旧模型仍在
        synchronized(stateLock) {
            val old = recognizer
            recognizer = null
            loadedModelDir = null
            if (old != null) {
                try {
                    old.release()
                } catch (e: Throwable) {
                    Log.w(TAG, "旧识别器释放异常: ${e.message}")
                }
            }
        }
        if (destroyed) return "引擎已释放"
        val newRecognizer = try {
            OnlineRecognizer(
                config = OnlineRecognizerConfig(
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = encoder.path,
                            decoder = decoder.path,
                            joiner = joiner.path,
                        ),
                        tokens = tokens.path,
                        numThreads = numThreads,
                        provider = "cpu",
                    ),
                    // 引擎内置端点检测：尾部静默自动断句（onFinal 触发点）
                    enableEndpoint = true,
                )
            )
        } catch (e: Throwable) {
            Log.e(TAG, "模型加载失败: ${modelDir.path}", e)
            return "模型加载失败: ${e.message}"
        }
        // 加载期间（数百 ms～数秒）Service 可能已销毁：写回前检查守卫，
        // 已释放则立即归还新实例，避免数百 MB native 内存复活常驻
        synchronized(stateLock) {
            if (destroyed) {
                try {
                    newRecognizer.release()
                } catch (e: Throwable) {
                    Log.w(TAG, "丢弃迟到模型异常: ${e.message}")
                }
                return "引擎已释放"
            }
            recognizer = newRecognizer
            loadedModelDir = modelDir
        }
        return null
    }

    override fun startSession(listener: SpeechRecognizerEngine.Listener): String? {
        val rec = recognizer ?: return "模型未加载"
        stopSession() // 幂等清理上一轮（含异常终止后遗留的流）
        this.listener = listener
        lastPartial = ""

        val newStream = try {
            rec.createStream()
        } catch (e: Throwable) {
            Log.e(TAG, "识别流创建失败", e)
            return "识别流创建失败: ${e.message}"
        }
        stream = newStream
        sessionActive = true

        val sessionListener = listener
        // Kotlin 闭包不能前向引用构造中的局部变量，用 holder 解耦自引用
        var captureHolder: AudioCapture? = null
        val audioCapture = AudioCapture(
            onSamples = { samples, length -> decodeChunk(newStream, samples, length) },
            onError = { msg ->
                // 迟到回调防护：stopSession 已将会话置为不活跃时不再投递
                if (sessionActive) {
                    sessionActive = false
                    sessionListener.onError(msg)
                }
                // 防御性兜底：采集线程自身调用 stop() 不会 join 自己，
                // 提前释放录音设备，避免占用麦克风直到调用方 stopSession
                captureHolder?.stop()
            },
        )
        captureHolder = audioCapture
        capture = audioCapture
        val captureError = audioCapture.start()
        if (captureError != null) {
            sessionActive = false
            capture = null
            stream = null
            newStream.release()
            this.listener = null
            return captureError
        }
        return null
    }

    override fun stopSession() {
        val wasActive = sessionActive
        sessionActive = false
        // 非采集线程调用时内部循环 join；返回 false 表示线程仍存活，
        // 此时宁可泄漏识别流也不得并发访问/释放 native 对象（避免 SIGSEGV）
        val captureExited = capture?.stop() ?: true
        capture = null

        val rec = recognizer
        val s = stream
        stream = null
        if (rec != null && s != null) {
            if (!captureExited) {
                Log.w(TAG, "采集线程未在时限内退出，放弃尾段冲刷与流释放（防并发崩溃）")
                lastPartial = ""
                listener = null
                return
            }
            // 尾段冲刷：把尚未端点确认的残余文本作为本会话最后一个 final 段
            // （注意：在 stopSession 调用线程投递，见接口 KDoc 的线程契约）
            try {
                s.inputFinished()
                while (rec.isReady(s)) rec.decode(s)
                val tail = rec.getResult(s).text
                if (wasActive && tail.isNotEmpty()) {
                    listener?.onFinal(VoiceTextCaseRestorer.restore(tail))
                }
            } catch (e: Throwable) {
                Log.w(TAG, "尾段冲刷异常: ${e.message}")
            } finally {
                try {
                    s.release()
                } catch (e: Throwable) {
                    Log.w(TAG, "识别流释放异常: ${e.message}")
                }
            }
        }
        lastPartial = ""
        listener = null
    }

    override fun release() {
        stopSession()
        synchronized(stateLock) {
            destroyed = true
            val rec = recognizer
            recognizer = null
            loadedModelDir = null
            if (rec != null) {
                try {
                    rec.release()
                } catch (e: Throwable) {
                    Log.w(TAG, "识别器释放异常: ${e.message}")
                }
            }
        }
    }

    /** 采集线程上的逐块解码：喂样本 → 解码 → partial 增量回调 → 端点判定。 */
    private fun decodeChunk(stream: OnlineStream, samples: FloatArray, length: Int) {
        if (!sessionActive) return
        val rec = recognizer ?: return
        try {
            val chunk = if (length == samples.size) samples else samples.copyOf(length)
            stream.acceptWaveform(chunk, AudioCapture.SAMPLE_RATE)
            while (rec.isReady(stream)) rec.decode(stream)

            // 大小写恢复：中英混合模型英文产出全大写，统一在输出层恢复句子大小写
            val text = VoiceTextCaseRestorer.restore(rec.getResult(stream).text)
            if (text.isNotEmpty() && text != lastPartial) {
                lastPartial = text
                listener?.onPartial(text)
            }
            if (rec.isEndpoint(stream)) {
                if (text.isNotEmpty()) {
                    listener?.onFinal(text)
                }
                rec.reset(stream)
                lastPartial = ""
            }
        } catch (e: Throwable) {
            Log.e(TAG, "解码异常", e)
            sessionActive = false
            listener?.onError("识别解码异常: ${e.message}")
        }
    }
}
