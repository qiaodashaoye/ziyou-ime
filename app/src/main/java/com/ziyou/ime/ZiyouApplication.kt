package com.ziyou.ime

import android.app.Application
import android.util.Log
import com.ziyou.ime.update.AppUpdateManager

/**
 * 字由输入法 应用入口
 * 负责全局初始化，资源部署和Rime引擎初始化延迟到RimeSession中异步执行
 */
class ZiyouApplication : Application() {

    companion object {
        private const val TAG = "Ziyou"

        lateinit var instance: ZiyouApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "字由输入法 Application 初始化")
        // 资源部署已移至 RimeSession.initialize() 中异步执行，避免阻塞主线程

        // 应用内更新自动检测：仅主进程触发（频控 24h/次，后台静默检测只暂存结果），
        // 键盘输入法服务不参与任何更新逻辑，弹窗等有 Activity 前台时才展示，
        // 不打扰输入（见 update/AppUpdateManager）
        if (AppUpdateManager.isMainProcess(this)) {
            AppUpdateManager.scheduleAutoCheckIfNeeded(this)
        }
    }
}
