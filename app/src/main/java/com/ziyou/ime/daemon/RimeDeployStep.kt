package com.ziyou.ime.daemon

import android.content.Context

/**
 * 引擎启动前的部署步骤抽象。
 *
 * [RimeSession] 此前直接调用 AssetDeployer / DictManager，形成 daemon 层对
 * config / dict 业务域的向上依赖。抽象为部署步骤后，具体实现由组合根
 * [com.ziyou.ime.di.AppContainer.install] 装配注入，daemon 层恢复「单向向下」依赖，
 * RimeSession 也得以脱离业务模块独立测试。
 *
 * 步骤在 IO 线程按注册顺序串行执行（initialize / redeploy 时均会重新执行）。
 */
fun interface RimeDeployStep {

    /** 引擎 startup 之前执行（IO 线程），如部署 assets 资源、重生成主词库。 */
    suspend fun beforeStartup(context: Context)
}
