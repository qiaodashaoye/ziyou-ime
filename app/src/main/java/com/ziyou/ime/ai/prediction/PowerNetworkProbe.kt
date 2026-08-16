package com.ziyou.ime.ai.prediction

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.util.Log
import com.ziyou.ime.core.prediction.HeavyRequestGate

/**
 * 网络计量性与电量状态探针（耗电审计 P0）。
 *
 * 为 LLM 预测的重请求门控（预取/预热，见 [HeavyRequestGate]）提供环境输入。
 * 全部为即时读取，无常驻监听/定时器：
 * - 电量经 [Intent.ACTION_BATTERY_CHANGED] 粘性广播读取（registerReceiver(null)
 *   只取最后一次广播值，不注册接收器，零唤醒成本）；
 * - 网络计量性经 [ConnectivityManager.isActiveNetworkMetered] 即时判定。
 *
 * 读取失败一律按「放行」降级（100% 电量 / 非计量），宁放过不误杀——
 * 门控是省电优化项，不得因探测异常关闭核心增强功能。
 */
object PowerNetworkProbe {

    private const val TAG = "PowerNetworkProbe"

    /** 当前活跃网络是否为计量网络（移动数据）；读取失败按非计量放行。 */
    fun isMeteredNetwork(context: Context): Boolean = try {
        context.getSystemService(ConnectivityManager::class.java)
            ?.isActiveNetworkMetered ?: false
    } catch (e: Exception) {
        Log.w(TAG, "读取网络计量性失败: ${e.message}")
        false
    }

    /**
     * 当前电量状态：(电量百分比 0~100, 是否充电中)。
     * 读取失败返回 (100, false) 不误杀。
     */
    fun batteryState(context: Context): Pair<Int, Boolean> = try {
        val sticky: Intent? = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        if (sticky == null) {
            100 to false
        } else {
            val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else 100
            val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            percent to charging
        }
    } catch (e: Exception) {
        Log.w(TAG, "读取电量状态失败: ${e.message}")
        100 to false
    }

    /** 综合门控判定：供组合根注入 [LlmPredictionCoordinator.allowHeavyRequests]。 */
    fun allowHeavyRequests(context: Context): Boolean {
        val (percent, charging) = batteryState(context)
        return HeavyRequestGate.allowHeavyRequest(isMeteredNetwork(context), percent, charging)
    }
}
