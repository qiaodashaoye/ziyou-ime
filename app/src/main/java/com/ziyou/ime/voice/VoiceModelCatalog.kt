package com.ziyou.ime.voice

/**
 * 语音模型目录（硬编码清单，非远程 catalog——模型来源完全由本文件定义，
 * 无远程目录解析的攻击面；下载安全基线见 [VoiceModelDownloader]）。
 *
 * 文件名与 sherpa-onnx 官方 HuggingFace 仓库逐一对齐
 * （见 OnlineRecognizer getModelConfig 的同款组合）；升级模型版本时
 * 同步更新 [repoPath] 与 [files]。
 *
 * 许可证注记：模型权重随各自仓库许可分发（zipformer 系列 Apache-2.0），
 * 下载目录保留模型出处说明（[sourceUrl]）供审计。
 */
object VoiceModelCatalog {

    /** 单个模型规格：目录 id + 展示信息 + HF 仓库与文件清单。 */
    data class VoiceModelSpec(
        /** 本地目录名与持久化 id（勿改，改了等同卸载重装） */
        val id: String,
        /** 设置页展示名 */
        val name: String,
        /** 一句话说明（语言/体积/场景） */
        val summary: String,
        /** HuggingFace 仓库路径（经镜像下载） */
        val repoPath: String,
        /** 模型目录内必备文件（与 SherpaOnnxEngine.loadModel 的目录约定一致） */
        val files: List<String>,
        /** 逐文件 sha256 锚定值（上游仓库实测，防镜像投毒/传输截断；
         *  升级模型版本时必须同步更新） */
        val sha256s: Map<String, String>,
        /** 模型出处（审计/许可追溯） */
        val sourceUrl: String,
    )

    /** 普通话标准流式模型（默认推荐，WenetSpeech 训练，中文流式第一梯队） */
    val ZH_STANDARD = VoiceModelSpec(
        id = "streaming-zipformer-zh-int8-2025-06-30",
        name = "普通话 · 标准",
        summary = "中文流式识别标准档，约 160MB，准确率优先",
        repoPath = "csukuangfj/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30",
        files = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt"),
        sha256s = mapOf(
            "encoder.int8.onnx" to "5ac51e27981bb4dab01bb9be4958453ba50c3b61c063ddda0eab23fd3671aa4f",
            "decoder.onnx" to "06522ad63cec0fdf6809f4e1db9bb4f7d710c34582e3b35db62ac60eccafac7e",
            "joiner.int8.onnx" to "b34584dc6f561089e1d747fedebb3765f2caa72c927ef54d7ca55e5ae40a814b",
            "tokens.txt" to "6193c7ea1c96d0d9a1e9652789b40d13a8a913b434a5451e93158f5a09fd6652",
        ),
        sourceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30",
    )

    /** 中英混合流式模型（夹杂英文单词的聊天场景） */
    val ZH_EN_BILINGUAL = VoiceModelSpec(
        id = "streaming-zipformer-bilingual-zh-en-2023-02-20",
        name = "中英混合",
        summary = "普通话 + 英文混说，约 190MB",
        repoPath = "csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
        files = listOf(
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt",
        ),
        sha256s = mapOf(
            "encoder-epoch-99-avg-1.int8.onnx" to "8fa764187a261844f859d7143ebaa563af5d10adfece4c18a8f414c88cba2a9b",
            "decoder-epoch-99-avg-1.onnx" to "2e3b5ec371f8899ee6acd829fd753ba45772df57a91bdf37cde3136354e7db7d",
            "joiner-epoch-99-avg-1.int8.onnx" to "1ed689c5ed19dbaa725d9d191bb4822b5f4855a39e1ffd28cbc1f340d25b2ee0",
            "tokens.txt" to "a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3",
        ),
        sourceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
    )

    /** 轻量流式模型（低端机/存储紧张时的降级档） */
    val ZH_LIGHT = VoiceModelSpec(
        id = "streaming-zipformer-zh-14M-2023-02-23",
        name = "普通话 · 轻量",
        summary = "14M 参数轻量档，约 24MB，低端机可用",
        repoPath = "csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23",
        files = listOf(
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt",
        ),
        sha256s = mapOf(
            "encoder-epoch-99-avg-1.int8.onnx" to "1c556ea57cec304e55ec4b72e52c1cc098bb01476ed7d90f3de939fe126487b1",
            "decoder-epoch-99-avg-1.onnx" to "5ee0f03a2768ff1d5c83ef3a493243c7935d316cd41280037b14783a3467cc78",
            "joiner-epoch-99-avg-1.int8.onnx" to "a7cf9d82757bdcf786059454495a9ca95e4bd7347f72473fc08d794475c36169",
            "tokens.txt" to "8b294db9045d6e5f94647f4c1eec1af4da143a75053c399611444b378ff966ac",
        ),
        sourceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23",
    )

    /** 全部可选模型（设置页展示顺序 = 推荐顺序） */
    val ALL: List<VoiceModelSpec> = listOf(ZH_STANDARD, ZH_EN_BILINGUAL, ZH_LIGHT)

    /** 默认激活模型（未配置时的回退） */
    val DEFAULT: VoiceModelSpec = ZH_STANDARD

    /** 按 id 查找，未知 id 返回 null */
    fun byId(id: String): VoiceModelSpec? = ALL.firstOrNull { it.id == id }
}
