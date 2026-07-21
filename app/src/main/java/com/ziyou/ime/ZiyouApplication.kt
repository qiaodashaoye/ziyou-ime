package com.ziyou.ime

import android.app.Application
import android.util.Log

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
    }
}
