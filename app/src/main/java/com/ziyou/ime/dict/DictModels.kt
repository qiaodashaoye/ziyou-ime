package com.ziyou.ime.dict

/**
 * 扩展词库数据模型
 */

/** 词库分类 */
enum class DictCategory(val displayName: String) {
    /** 基础词库扩展包：原内置的大词库（ext/tencent/others）移出主包后按需下载 */
    BASE("基础词库"),
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
    val author: String,
    /** 词库文件的 SHA-256（十六进制，可选）。下载后校验，为空则跳过校验（向后兼容）。 */
    val sha256: String = "",
    /** 产物类型：[KIND_DICT]=Rime dict.yaml（注入 import_tables）；
     *  [KIND_PREDICT_DB]=predict.db 联想子库（整体替换用户目录 predict.db，
     *  见 PredictDbManager）。旧 catalog 无此字段时默认 dict（向后兼容）。 */
    val kind: String = KIND_DICT
) {
    val dictCategory: DictCategory
        get() = DictCategory.fromValue(category)

    /** 是否为 predict.db 联想子库产物 */
    val isPredictDb: Boolean
        get() = kind == KIND_PREDICT_DB

    val sizeDisplay: String
        get() = when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> String.format("%.1f MB", size / (1024.0 * 1024.0))
        }

    companion object {
        /** 产物类型：Rime dict.yaml 扩展词库 */
        const val KIND_DICT = "dict"
        /** 产物类型：predict.db 联想子库（整体替换） */
        const val KIND_PREDICT_DB = "predict_db"

        /**
         * 合法词库 ID 正则：仅允许字母 / 数字 / 下划线 / 连字符。
         * 用于阻断恶意 catalog 通过 id（如 "../../foo"）拼接文件名造成的路径穿越写盘。
         */
        private val ID_PATTERN = Regex("^[A-Za-z0-9_-]+$")

        /** 校验词库 id 是否合法（非空且仅含安全字符）。 */
        fun isValidId(id: String): Boolean = id.isNotEmpty() && ID_PATTERN.matches(id)
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
