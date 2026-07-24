package com.ziyou.ime.daemon

import android.content.Context
import com.ziyou.ime.core.RimeApi
import com.ziyou.ime.core.RimeMessage
import kotlinx.coroutines.flow.SharedFlow

/**
 * Rime 引擎生命周期抽象。
 *
 * 为解耦而引入：Service / UI / 业务域此前直接依赖 [RimeSession] 单例，无法注入替身、难以测试。
 * 抽象出本接口后，调用方可依赖 [RimeEngine] 而非具体单例，测试时可注入 fake 实现。
 * 生产实现为 [RimeSession]（object），通过 [com.ziyou.ime.di.AppContainer] 提供。
 */
interface RimeEngine {

    /** 引擎操作接口（挂起、线程安全）。 */
    val api: RimeApi

    /** 引擎消息流（方案切换、选项变更、部署状态）。 */
    val messageFlow: SharedFlow<RimeMessage>

    /** 是否已初始化。 */
    val initialized: Boolean

    /**
     * 初始化引擎（部署资源、注入扩展词库、启动引擎）。
     * @param fullCheck 首次安装/升级后为 true，以完整维护编译新方案。
     */
    suspend fun initialize(context: Context, fullCheck: Boolean = false)

    /** 词库/配置变更后重新部署引擎。 */
    suspend fun redeploy(context: Context)

    /** 销毁会话，释放资源。 */
    suspend fun destroy()
}
