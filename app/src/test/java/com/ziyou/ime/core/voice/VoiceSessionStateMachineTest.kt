package com.ziyou.ime.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [VoiceSessionStateMachine] 转移表测试。 */
class VoiceSessionStateMachineTest {

    private val fsm = VoiceSessionStateMachine()

    @Test
    fun `初始为 IDLE 且非活跃`() {
        assertEquals(VoicePhase.IDLE, fsm.phase)
        assertFalse(fsm.isActive)
        assertEquals(0, fsm.utteranceCount)
    }

    @Test
    fun `Start 进入 LISTENING`() {
        assertEquals(VoicePhase.LISTENING, fsm.onEvent(VoiceSessionEvent.Start))
        assertTrue(fsm.isActive)
    }

    @Test
    fun `非 IDLE 态的 Start 被忽略`() {
        fsm.onEvent(VoiceSessionEvent.Start)
        assertEquals(VoicePhase.LISTENING, fsm.onEvent(VoiceSessionEvent.Start))
    }

    @Test
    fun `完整单句会话路径`() {
        fsm.onEvent(VoiceSessionEvent.Start)
        fsm.onEvent(VoiceSessionEvent.SpeechDetected)
        assertEquals(VoicePhase.SPEAKING, fsm.phase)
        fsm.onEvent(VoiceSessionEvent.UtteranceEnd)
        assertEquals(VoicePhase.COOLDOWN, fsm.phase)
        assertEquals(1, fsm.utteranceCount)
        fsm.onEvent(VoiceSessionEvent.SilenceTimeout)
        assertEquals(VoicePhase.IDLE, fsm.phase)
        assertTrue(fsm.autoStopped)
    }

    @Test
    fun `LISTENING 态静默超时自动收尾`() {
        fsm.onEvent(VoiceSessionEvent.Start)
        fsm.onEvent(VoiceSessionEvent.SilenceTimeout)
        assertEquals(VoicePhase.IDLE, fsm.phase)
        assertTrue(fsm.autoStopped)
        assertFalse(fsm.isActive)
    }

    @Test
    fun `COOLDOWN 态续说回到 SPEAKING 且句数累计`() {
        fsm.onEvent(VoiceSessionEvent.Start)
        fsm.onEvent(VoiceSessionEvent.SpeechDetected)
        fsm.onEvent(VoiceSessionEvent.UtteranceEnd)
        assertEquals(VoicePhase.SPEAKING, fsm.onEvent(VoiceSessionEvent.SpeechDetected))
        assertFalse(fsm.autoStopped)
        fsm.onEvent(VoiceSessionEvent.UtteranceEnd)
        assertEquals(2, fsm.utteranceCount)
    }

    @Test
    fun `UserStop 任何状态下立即回到 IDLE 且不算自动收尾`() {
        fsm.onEvent(VoiceSessionEvent.Start)
        fsm.onEvent(VoiceSessionEvent.SpeechDetected)
        assertEquals(VoicePhase.IDLE, fsm.onEvent(VoiceSessionEvent.UserStop))
        assertFalse(fsm.autoStopped)
    }

    @Test
    fun `SPEAKING 态的 SilenceTimeout 也自动收尾`() {
        // 回归：超时定时器会在 onPartial 后于 SPEAKING 态 arm；若到期被忽略且不再续期
        // （端点未如期断句），会话将永久脱离超时保护，麦克风与解码无限运行导致持续发热
        fsm.onEvent(VoiceSessionEvent.Start)
        fsm.onEvent(VoiceSessionEvent.SpeechDetected)
        assertEquals(VoicePhase.SPEAKING, fsm.phase)
        assertEquals(VoicePhase.IDLE, fsm.onEvent(VoiceSessionEvent.SilenceTimeout))
        assertTrue(fsm.autoStopped)
        assertFalse(fsm.isActive)
    }

    @Test
    fun `UtteranceEnd 仅 SPEAKING 态生效`() {
        fsm.onEvent(VoiceSessionEvent.Start)
        fsm.onEvent(VoiceSessionEvent.UtteranceEnd)
        assertEquals(VoicePhase.LISTENING, fsm.phase)
        assertEquals(0, fsm.utteranceCount)
    }

    @Test
    fun `Reset 清零句数与阶段`() {
        fsm.onEvent(VoiceSessionEvent.Start)
        fsm.onEvent(VoiceSessionEvent.SpeechDetected)
        fsm.onEvent(VoiceSessionEvent.UtteranceEnd)
        fsm.onEvent(VoiceSessionEvent.Reset)
        assertEquals(VoicePhase.IDLE, fsm.phase)
        assertEquals(0, fsm.utteranceCount)
    }

    @Test
    fun `Start 开启新一轮时句数清零`() {
        fsm.onEvent(VoiceSessionEvent.Start)
        fsm.onEvent(VoiceSessionEvent.SpeechDetected)
        fsm.onEvent(VoiceSessionEvent.UtteranceEnd)
        fsm.onEvent(VoiceSessionEvent.UserStop)
        fsm.onEvent(VoiceSessionEvent.Start)
        assertEquals(0, fsm.utteranceCount)
    }

    @Test
    fun `autoStopped 仅保持一次事件周期`() {
        fsm.onEvent(VoiceSessionEvent.Start)
        fsm.onEvent(VoiceSessionEvent.SilenceTimeout)
        assertTrue(fsm.autoStopped)
        fsm.onEvent(VoiceSessionEvent.Start)
        assertFalse(fsm.autoStopped)
    }
}
