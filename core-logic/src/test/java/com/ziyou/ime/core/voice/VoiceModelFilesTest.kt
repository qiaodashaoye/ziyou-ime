package com.ziyou.ime.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [VoiceModelFiles] 模型目录就绪判定测试。 */
class VoiceModelFilesTest {

    @Test
    fun `完整 transducer 目录就绪`() {
        val missing = VoiceModelFiles.checkReady(
            listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt")
        )
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `兼容带 epoch 后缀的命名`() {
        val missing = VoiceModelFiles.checkReady(
            listOf(
                "encoder-epoch-99-avg-1.int8.onnx",
                "decoder-epoch-99-avg-1.onnx",
                "joiner-epoch-99-avg-1.int8.onnx",
                "tokens.txt",
                "SOURCE.txt",
            )
        )
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `缺 tokens 与 joiner 时报对应缺失项`() {
        val missing = VoiceModelFiles.checkReady(
            listOf("encoder.int8.onnx", "decoder.onnx")
        )
        assertEquals(2, missing.size)
        assertTrue("tokens.txt" in missing)
        assertTrue("joiner*.onnx" in missing)
    }

    @Test
    fun `空目录四项全缺`() {
        val missing = VoiceModelFiles.checkReady(emptyList())
        assertEquals(4, missing.size)
    }

    @Test
    fun `非 onnx 的同前缀文件不算权重`() {
        val missing = VoiceModelFiles.checkReady(
            listOf("encoder.txt", "decoder.onnx", "joiner.int8.onnx", "tokens.txt")
        )
        assertEquals(listOf("encoder*.onnx"), missing)
    }

    @Test
    fun `下载残留 part 文件不算就绪`() {
        val missing = VoiceModelFiles.checkReady(
            listOf("encoder.int8.onnx.part", "decoder.onnx", "joiner.int8.onnx", "tokens.txt")
        )
        assertEquals(listOf("encoder*.onnx"), missing)
    }
}
