package com.ziyou.ime.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * 16kHz / 单声道 / PCM16 麦克风采集（专用读取线程）。
 *
 * 为流式 ASR 设计：读取循环把 PCM16 转为 [-1, 1] float 样本并经 [onSamples] 回调；
 * **回调在采集线程同步执行**，消费者应即时处理（sherpa-onnx 解码即在同线程逐块进行），
 * 不得做阻塞操作，否则会拖慢下一次读取、增大识别延迟。
 *
 * 隐私纪律：样本只在内存中流向本地推理引擎，不写任何文件。
 */
class AudioCapture(
    private val onSamples: (samples: FloatArray, length: Int) -> Unit,
    private val onError: (message: String) -> Unit,
) {

    companion object {
        private const val TAG = "AudioCapture"

        /** ASR 标准采样率（sherpa-onnx FeatureConfig 默认值）。 */
        const val SAMPLE_RATE = 16000

        /** 每次读取 100ms 样本（1600 @16kHz），兼顾识别延迟与读取开销。 */
        private const val CHUNK_SAMPLES = 1600

        /** 等待采集线程退出的总上限（读取循环单轮上限约 100ms+解码，2s 已极度宽裕）。 */
        private const val JOIN_TIMEOUT_MS = 2000L
    }

    @Volatile
    private var running = false

    private var recorder: AudioRecord? = null
    private var thread: Thread? = null

    /** 是否正在采集。 */
    val isCapturing: Boolean get() = running

    /**
     * 初始化并开始采集。
     *
     * @return null 表示成功；否则为失败原因。
     * 注：RECORD_AUDIO 运行时权限由调用方（面板权限引导态）保证，此处不再检查。
     */
    @SuppressLint("MissingPermission")
    fun start(): String? {
        if (running) return null
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) return "设备不支持 ${SAMPLE_RATE}Hz 单声道录音"

        val created = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBufferSize, CHUNK_SAMPLES * 2)
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord 创建失败", e)
            return "录音设备创建失败: ${e.message}"
        }
        if (created.state != AudioRecord.STATE_INITIALIZED) {
            created.release()
            return "录音设备初始化失败（可能被系统或其他应用占用）"
        }

        try {
            created.startRecording()
        } catch (e: IllegalStateException) {
            created.release()
            return "录音启动失败: ${e.message}"
        }

        recorder = created
        running = true
        thread = Thread({ readLoop(created) }, "voice-audio-capture").also { it.start() }
        return null
    }

    /**
     * 停止采集并释放录音资源（幂等）。
     *
     * @return 采集线程是否已确定退出（在采集线程自身调用时视为已退出）。
     *         返回 false 表示等待超时线程仍存活，调用方**不得**再访问共享的
     *         识别流等 native 对象（宁可小泄漏不可并发访问）。
     */
    fun stop(): Boolean {
        running = false
        val worker = thread
        if (worker != null && Thread.currentThread() != worker) {
            // 读取循环有天然界（read 每轮 ≤100ms + 有限解码），线程必然退出；
            // 循环 join 直到退出或总超时，绝不带“可放弃的短超时”静默继续
            val deadline = System.currentTimeMillis() + JOIN_TIMEOUT_MS
            try {
                while (worker.isAlive && System.currentTimeMillis() < deadline) {
                    worker.join(50)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        thread = null
        releaseRecorder()
        return worker == null || !worker.isAlive
    }

    private fun releaseRecorder() {
        val r = recorder ?: return
        recorder = null
        try {
            if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioRecord.stop 异常: ${e.message}")
        }
        r.release()
    }

    private fun readLoop(record: AudioRecord) {
        val shorts = ShortArray(CHUNK_SAMPLES)
        // 缓冲复用安全：onSamples 在本线程同步消费，sherpa acceptWaveform 内部即拷贝
        val floats = FloatArray(CHUNK_SAMPLES)
        while (running) {
            val n = try {
                record.read(shorts, 0, shorts.size)
            } catch (e: Exception) {
                Log.e(TAG, "读取异常", e)
                -1
            }
            if (n > 0) {
                for (i in 0 until n) floats[i] = shorts[i] / 32768f
                if (!running) break
                try {
                    onSamples(floats, n)
                } catch (e: Exception) {
                    Log.e(TAG, "样本消费异常", e)
                    onError("识别处理异常: ${e.message}")
                    break
                }
            } else if (n < 0) {
                Log.e(TAG, "麦克风读取失败 code=$n")
                onError("麦克风读取失败(code=$n)")
                break
            }
        }
        running = false
    }
}
