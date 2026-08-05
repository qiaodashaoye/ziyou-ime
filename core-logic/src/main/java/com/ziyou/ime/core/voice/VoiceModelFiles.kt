package com.ziyou.ime.core.voice

/**
 * 语音模型目录就绪检查（纯逻辑，不依赖 Android 类型）。
 *
 * sherpa-onnx 流式 transducer 模型目录约定：encoder/decoder/joiner 三个
 * ONNX 权重（前缀匹配，兼容 int8/fp16 命名）+ tokens.txt 词表。
 * 下载管理器与识别引擎共用同一判定口径，避免两处实现漂移。
 */
object VoiceModelFiles {

    /** 三段权重的文件名前缀（兼容 int8/fp16/epoch 后缀命名）。 */
    val WEIGHT_PREFIXES = listOf("encoder", "decoder", "joiner")

    /**
     * 检查目录内文件名列表是否满足模型就绪条件。
     *
     * @param fileNames 目录下的文件名（不含路径）
     * @return 缺失项描述列表；空列表表示就绪
     */
    fun checkReady(fileNames: List<String>): List<String> {
        val missing = mutableListOf<String>()
        if ("tokens.txt" !in fileNames) missing += "tokens.txt"
        for (prefix in WEIGHT_PREFIXES) {
            val hit = fileNames.any { it.startsWith(prefix) && it.endsWith(".onnx") }
            if (!hit) missing += "$prefix*.onnx"
        }
        return missing
    }
}
