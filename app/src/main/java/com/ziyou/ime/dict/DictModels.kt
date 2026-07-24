package com.ziyou.ime.dict

/**
 * 扩展词库数据模型
 */

/** 词库分类 */
enum class DictCategory(val displayName: String) {
    CLASSICAL("古典文学"),
    PROFESSIONAL("专业行业"),
    DIALECT("地方方言"),
    INTERNET("网络流行"),
    SOCIAL("社交聊天");

    companion object {
        fun fromValue(value: String): DictCategory {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: PROFESSIONAL
        }
    }
}

/** 远程词库信息（来自 catalog.json） */
data class RemoteDictInfo(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val version: String,
    val url: String,
    val size: Long,
    val author: String
) {
    val dictCategory: DictCategory
        get() = DictCategory.fromValue(category)

    val sizeDisplay: String
        get() = when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> String.format("%.1f MB", size / (1024.0 * 1024.0))
        }
}

/** 本地已安装词库信息 */
data class InstalledDictInfo(
    val id: String,
    val version: String,
    val enabled: Boolean,
    val installedAt: Long
)

/** 远程词库目录（catalog.json 的完整结构） */
data class DictCatalog(
    val version: Int,
    val dictionaries: List<RemoteDictInfo>
)

/** 本地安装记录（ext_dicts.json 的完整结构） */
data class InstalledDictsConfig(
    val installed: List<InstalledDictInfo>
)

/** 下载进度状态 */
sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val dictId: String, val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : DownloadState()
    data class Success(val dictId: String) : DownloadState()
    data class Error(val dictId: String, val message: String) : DownloadState()
}

/** 部署状态 */
sealed class DeployState {
    data object Idle : DeployState()
    data object Deploying : DeployState()
    data object Done : DeployState()
    data class Failed(val message: String) : DeployState()
}

/** 词库预览中的单条词条 */
data class DictEntry(
    val word: String,
    val code: String
)

/** 词库预览数据 */
data class DictPreview(
    val dictInfo: RemoteDictInfo,
    val entries: List<DictEntry>,
    val totalEntriesHint: Int
)

/** 词库预览加载状态 */
sealed class PreviewState {
    data object Idle : PreviewState()
    data class Loading(val dictId: String) : PreviewState()
    data class Success(val preview: DictPreview) : PreviewState()
    data class Error(val dictId: String, val message: String) : PreviewState()
}
