package com.ziyou.ime.voice

import java.io.File

/**
 * 流式语音识别引擎抽象（语音输入域核心接口，见 docs/实时语音输入可行性方案.md §5）。
 *
 * 实现纪律：
 * - 会话进行中的回调在**同一线程**（解码线程）发出；例外：[stopSession] 的
 *   尾段冲刷 onFinal 在 stopSession 调用线程投递——调用方处理 onFinal 时
 *   需容忍两个线程来源（建议统一切主线程后再更新状态）；
 * - 识别产出是成品文本，**不走 Rime 编码路径**——上屏统一经
 *   InputLogicController.commitDirectToEditor 直达宿主编辑器；
 * - 音频与文本均不落盘、不联网（隐私红线）。
 *
 * 生命周期：loadModel（重操作，勿在主线程）→ startSession/stopSession（可多轮）→ release。
 */
interface SpeechRecognizerEngine {

    /** 流式识别回调（在解码线程回调，非主线程）。 */
    interface Listener {
        /** 中间结果：仅用于面板预览渲染，禁止直接上屏（会被引擎反复改写）。 */
        fun onPartial(text: String)

        /** 端点确认的一段最终文本（上屏单位，配合 VoiceUtteranceBuffer.commitSegment 使用）。 */
        fun onFinal(text: String)

        /** 会话异常（音频采集失败/解码错误等），收到后会话已终止。 */
        fun onError(message: String)
    }

    /** 模型是否已加载完成（不区分加载的是哪个模型；精确判断用 [isModelLoadedFor]）。 */
    val isModelLoaded: Boolean

    /**
     * 指定模型目录是否已加载完成。
     *
     * 用户在设置页切换激活模型后，协调器据此决定是否需要重新加载：
     * 已加载目录与目标不一致时必须走 [loadModel] 重新加载，
     * 否则引擎会继续用旧模型识别。
     */
    fun isModelLoadedFor(modelDir: File): Boolean

    /**
     * 加载模型目录（重操作，数百 ms～数秒，**禁止主线程调用**）。
     *
     * 目录结构遵循 sherpa-onnx 流式 transducer 约定：
     * `encoder*.onnx` / `decoder*.onnx` / `joiner*.onnx` / `tokens.txt`。
     *
     * @return null 表示成功；否则为失败原因
     */
    fun loadModel(modelDir: File): String?

    /**
     * 开始一次识别会话（需要 RECORD_AUDIO 权限已由调用方确认）。
     * 若已有活跃会话，先停止旧会话；旧会话的尾段 onFinal 会投递给旧 Listener
     *（残余文本理应交付），调用方替换 listener 时需注意该时序。
     *
     * @return null 表示成功；否则为失败原因（如模型未加载、录音设备初始化失败）
     */
    fun startSession(listener: Listener): String?

    /** 停止会话并释放录音资源（幂等）；识别器实例保留供下一轮复用。 */
    fun stopSession()

    /** 释放全部资源（含识别器实例，IME 销毁时调用）。 */
    fun release()
}
