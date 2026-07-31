package com.ziyou.ime.core.routing

import com.ziyou.ime.core.routing.StartupRouteLogic.Destination
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * StartupRouteLogic 单元测试：输入法就绪状态到启动落地页的映射。
 */
class StartupRouteLogicTest {

    @Test
    fun `输入法未就绪时展示引导页`() {
        assertEquals(
            Destination.SETUP_GUIDE,
            StartupRouteLogic.route(
                imeReady = false, preferenceSetupDone = true, forceShowGuide = false
            )
        )
    }

    @Test
    fun `未就绪且偏好向导未完成时仍优先引导页`() {
        assertEquals(
            Destination.SETUP_GUIDE,
            StartupRouteLogic.route(
                imeReady = false, preferenceSetupDone = false, forceShowGuide = false
            )
        )
    }

    @Test
    fun `已就绪且偏好向导完成时直达设置页`() {
        assertEquals(
            Destination.SETTINGS,
            StartupRouteLogic.route(
                imeReady = true, preferenceSetupDone = true, forceShowGuide = false
            )
        )
    }

    @Test
    fun `已就绪但偏好向导未完成时先进偏好向导`() {
        assertEquals(
            Destination.PREFERENCE_WIZARD,
            StartupRouteLogic.route(
                imeReady = true, preferenceSetupDone = false, forceShowGuide = false
            )
        )
    }

    @Test
    fun `强制查看引导时即使已就绪也展示引导页`() {
        assertEquals(
            Destination.SETUP_GUIDE,
            StartupRouteLogic.route(
                imeReady = true, preferenceSetupDone = true, forceShowGuide = true
            )
        )
    }
}
