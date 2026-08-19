package com.ziyou.ime.core.routing

/**
 * 应用启动页路由的纯逻辑：根据输入法就绪状态决定落地页。
 *
 * 启动页（Launcher）本身是启用引导页，但用户完成启用与切换后，
 * 再次打开应用不应再看到引导，而应直达设置页；本对象只做状态到
 * 目的地的映射，不感知 Android 组件，可独立 JVM 单测。
 */
object StartupRouteLogic {

    /** 启动落地页 */
    enum class Destination {
        /** 启用引导页（输入法未就绪，或用户主动查看引导） */
        SETUP_GUIDE,
        /** 首启偏好向导（输入法已就绪但尚未完成初始偏好设置） */
        PREFERENCE_WIZARD,
        /** 设置页（输入法已就绪且偏好向导已完成） */
        SETTINGS,
    }

    /**
     * 决定启动落地页：
     * - [forceShowGuide] 为 true（如设置页「启用与切换引导」入口）时始终展示引导页；
     * - 输入法未就绪（未启用或未激活）时展示引导页；
     * - 已就绪但初始偏好向导未完成时先进偏好向导；
     * - 其余情况直达设置页。
     */
    fun route(
        imeReady: Boolean,
        preferenceSetupDone: Boolean,
        forceShowGuide: Boolean,
    ): Destination = when {
        forceShowGuide || !imeReady -> Destination.SETUP_GUIDE
        !preferenceSetupDone -> Destination.PREFERENCE_WIZARD
        else -> Destination.SETTINGS
    }
}
