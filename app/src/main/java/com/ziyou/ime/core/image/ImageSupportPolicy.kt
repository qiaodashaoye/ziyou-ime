package com.ziyou.ime.core.image

/**
 * 编辑器图片能力级别：驱动面板图片按钮呈现「发送」或「保存」。
 */
enum class ImageSupportLevel {
    /** 可经 Commit Content API 直接把图片发进当前输入框 */
    SEND,

    /** 编辑器不收图片富媒体，仅支持保存到系统相册 */
    SAVE_ONLY
}

/**
 * 已验证支持接收图片富媒体（commitContent）的应用白名单。
 *
 * 动态检测（EditorInfo.contentMimeTypes）是首要依据；本枚举兜底覆盖
 * 「实测可收图但未声明 contentMimeTypes」的应用。扩展方式：真机实测
 * 通过后在此追加一个枚举条目即可，无需改动任何检测/路由代码。
 */
enum class ImageCapableApp(val packageName: String) {
    /** 微信 */
    WECHAT("com.tencent.mm"),

    /** QQ */
    QQ("com.tencent.mobileqq"),

    /** TIM */
    TIM("com.tencent.tim"),

    /** 钉钉 */
    DINGTALK("com.alibaba.android.rimet"),

    /** Telegram */
    TELEGRAM("org.telegram.messenger");

    companion object {
        /** 包名集合（一次构建，O(1) 查询，热路径零分配） */
        private val PACKAGES: Set<String> = entries.mapTo(HashSet()) { it.packageName }

        /** 包名是否在白名单内（null 视为不支持） */
        fun contains(packageName: String?): Boolean =
            packageName != null && packageName in PACKAGES
    }
}

/**
 * 图片能力裁决（纯逻辑，Android 侧由 EditorImageSupport 提取入参）：
 * 编辑器声明了 image 通配 MIME（权威信号）或目标应用在 [ImageCapableApp]
 * 白名单内 → [ImageSupportLevel.SEND]，否则 [ImageSupportLevel.SAVE_ONLY]。
 */
object ImageSupportPolicy {

    fun resolve(editorDeclaresImageMime: Boolean, packageName: String?): ImageSupportLevel =
        if (editorDeclaresImageMime || ImageCapableApp.contains(packageName)) {
            ImageSupportLevel.SEND
        } else {
            ImageSupportLevel.SAVE_ONLY
        }
}
