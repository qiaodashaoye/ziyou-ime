package com.ziyou.ime

import android.app.Application
import android.util.Log
import com.ziyou.ime.config.AssetDeployer

/**
 * 字由输入法 应用入口
 * 负责全局初始化和Rime引擎的生命周期管理
 *
 * 初始化流程：
 * 1. 设置全局实例引用
 * 2. 部署Rime资源文件（首次安装或升级时）
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

        // 部署Rime资源文件（首次启动或版本升级时从assets复制到内部存储）
        AssetDeployer.deployIfNeeded(this)
    }
}
