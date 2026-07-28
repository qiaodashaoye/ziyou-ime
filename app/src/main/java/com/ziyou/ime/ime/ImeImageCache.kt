package com.ziyou.ime.ime

/**
 * 输入法待发送图片共享缓存目录（单一来源）。
 *
 * cacheDir 下的 [CACHE_DIR_NAME] 子目录已经由 FileProvider（authority
 * `${applicationId}.imecontent`，路径配置见 res/xml/ime_file_paths.xml）暴露为
 * content:// URI，供 commitContent 富媒体提交时临时授权目标应用读取。
 * AI 答案转图（[TextImageRenderer]）、涂鸦导出（[DoodleImageExporter]）与
 * 技能图片输出（SkillRuntime）共用此目录。
 */
object ImeImageCache {

    /** 待发送图片共享缓存子目录名（cacheDir 下，FileProvider 已暴露） */
    const val CACHE_DIR_NAME = "ime_images"
}
