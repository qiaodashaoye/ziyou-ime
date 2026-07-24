package com.ziyou.ime.di

import com.ziyou.ime.daemon.RimeEngine
import com.ziyou.ime.daemon.RimeSession

/**
 * 轻量依赖容器（手写 DI 的注入点）。
 *
 * 目的：把"全局单例硬依赖"逐步收敛到一个可替换的组合根（composition root）。
 * 现阶段作为解耦的第一步——新代码应通过 [AppContainer] 获取 [RimeEngine] 等协作对象，
 * 而非直接引用 [RimeSession] 单例；测试时可通过 [overrideRimeEngine] 注入 fake 实现。
 *
 * 由 [com.ziyou.ime.ZiyouApplication.onCreate] 初始化。后续可平滑迁移到 Hilt/Koin。
 */
object AppContainer {

    @Volatile
    private var rimeEngineOverride: RimeEngine? = null

    /** Rime 引擎（默认生产实现为 [RimeSession]，可被测试覆盖）。 */
    val rimeEngine: RimeEngine
        get() = rimeEngineOverride ?: RimeSession

    /** 测试注入点：用 fake 引擎替换默认实现。 */
    fun overrideRimeEngine(engine: RimeEngine?) {
        rimeEngineOverride = engine
    }
}
