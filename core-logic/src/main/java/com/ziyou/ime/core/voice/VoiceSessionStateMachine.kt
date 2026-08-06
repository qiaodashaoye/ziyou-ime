package com.ziyou.ime.core.voice

/**
 * 语音会话所处阶段。
 *
 * - [IDLE]：未开始或已停止；
 * - [LISTENING]：已开录但尚未检出语音（静默等待）；
 * - [SPEAKING]：正在说话（已有识别产出）；
 * - [COOLDOWN]：一句已确认，等待用户续说或超时收尾。
 */
enum class VoicePhase { IDLE, LISTENING, SPEAKING, COOLDOWN }

/**
 * 驱动 [VoiceSessionStateMachine] 的事件。
 *
 * 静默超时判定不在状态机内计时——由调用方（IME 层）观察识别产出节奏，
 * 在合适时机投递 [SilenceTimeout]，保持本类纯逻辑、可单测。
 */
sealed class VoiceSessionEvent {
    /** 用户按下录音键（仅 IDLE 态生效）。 */
    data object Start : VoiceSessionEvent()

    /** 检出语音活动（首个非空识别结果 / VAD 触发）。 */
    data object SpeechDetected : VoiceSessionEvent()

    /** 一句话经端点确认落段。 */
    data object UtteranceEnd : VoiceSessionEvent()

    /** 静默超时（任何活跃态均自动收尾：LISTENING=没说话；SPEAKING=窗口内未断句；
     * COOLDOWN=说完了）。 */
    data object SilenceTimeout : VoiceSessionEvent()

    /** 用户手动停止（任何状态下生效）。 */
    data object UserStop : VoiceSessionEvent()

    /** 完全复位（连同类计清零）。 */
    data object Reset : VoiceSessionEvent()
}

/**
 * 语音会话阶段状态机（纯逻辑）。
 *
 * 只回答两个问题：**会话进行到哪一步**、**最近一次事件是否触发自动收尾**。
 * 不持有任何音频/线程/定时器资源。
 *
 * 状态转移：
 * ```
 * IDLE --Start--> LISTENING --SpeechDetected--> SPEAKING --UtteranceEnd--> COOLDOWN
 *                LISTENING --SilenceTimeout--> IDLE（自动收尾：什么都没录到）
 *                SPEAKING  --SilenceTimeout--> IDLE（自动收尾：超时窗内未断句，
 *                                        防止定时器断链后会话脱离超时保护）
 *                COOLDOWN  --SpeechDetected--> SPEAKING（用户续说）
 *                COOLDOWN  --SilenceTimeout--> IDLE（自动收尾：说完了）
 * 任意状态 --UserStop/Reset--> IDLE
 * ```
 */
class VoiceSessionStateMachine {

    /** 当前阶段。 */
    var phase: VoicePhase = VoicePhase.IDLE
        private set

    /** 本会话累计确认落段的句数（供 UI/统计，[Start] 新一轮时清零）。 */
    var utteranceCount: Int = 0
        private set

    /** 最近一次事件是否触发自动收尾（调用方据此决定是否结束会话并上屏残余文本）。 */
    var autoStopped: Boolean = false
        private set

    /** 会话是否处于活跃状态（正在录音）。 */
    val isActive: Boolean get() = phase != VoicePhase.IDLE

    /**
     * 投递一个事件，返回转移后的阶段。
     *
     * 非法转移（如 SPEAKING 态收 Start）被静默忽略，保证调用方无需预判状态。
     */
    fun onEvent(event: VoiceSessionEvent): VoicePhase {
        autoStopped = false
        when (event) {
            VoiceSessionEvent.Reset -> {
                phase = VoicePhase.IDLE
                utteranceCount = 0
            }

            VoiceSessionEvent.Start -> if (phase == VoicePhase.IDLE) {
                phase = VoicePhase.LISTENING
                utteranceCount = 0
            }

            VoiceSessionEvent.SpeechDetected -> when (phase) {
                VoicePhase.LISTENING, VoicePhase.COOLDOWN -> phase = VoicePhase.SPEAKING
                else -> Unit
            }

            VoiceSessionEvent.UtteranceEnd -> if (phase == VoicePhase.SPEAKING) {
                utteranceCount++
                phase = VoicePhase.COOLDOWN
            }

            VoiceSessionEvent.SilenceTimeout -> when (phase) {
                // SPEAKING 也须响应：超时定时器在 onPartial 后会于 SPEAKING 态 arm，
                // 若到期被忽略且不再续期（端点未如期断句），会话将永久脱离超时保护，
                // 麦克风与解码线程无限运行——持续发热的主要逃逸口
                VoicePhase.LISTENING, VoicePhase.SPEAKING, VoicePhase.COOLDOWN -> {
                    phase = VoicePhase.IDLE
                    autoStopped = true
                }

                else -> Unit
            }

            VoiceSessionEvent.UserStop -> phase = VoicePhase.IDLE
        }
        return phase
    }
}
