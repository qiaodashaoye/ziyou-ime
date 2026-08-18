package com.ziyou.ime.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Rime引擎通知消息
 * 由JNI层回调触发，通过SharedFlow分发给UI层
 */
sealed class RimeMessage {
    /** Schema切换通知 */
    data class SchemaMessage(val schemaId: String) : RimeMessage()

    /** 选项变更通知（如切换中英文、简繁等） */
    data class OptionMessage(val option: String) : RimeMessage()

    /** 部署状态通知 */
    data class DeployMessage(val status: String) : RimeMessage()

    /** 未知消息类型 */
    data class UnknownMessage(val type: Int, val value: String) : RimeMessage()
}

/**
 * Rime消息分发器
 * 接收JNI层回调，通过SharedFlow广播给所有订阅者
 */
object RimeMessageHandler {
    private val _messageFlow = MutableSharedFlow<RimeMessage>(
        replay = 1,
        extraBufferCapacity = 16
    )

    /** 消息流，UI层订阅此Flow获取Rime状态变更通知 */
    val messageFlow: SharedFlow<RimeMessage> = _messageFlow.asSharedFlow()

    internal fun onMessage(message: RimeMessage) {
        _messageFlow.tryEmit(message)
    }
}
