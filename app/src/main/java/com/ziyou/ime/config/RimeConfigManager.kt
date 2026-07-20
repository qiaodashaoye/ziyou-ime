package com.ziyou.ime.config

import android.util.Log
import com.ziyou.ime.core.RimeConfig

/**
 * Rime配置管理器
 * 封装JNI层的配置操作，提供安全且便捷的Kotlin API
 * 
 * 使用示例：
 * ```kotlin
 * // 读取默认配置中的页大小
 * val pageSize = RimeConfigManager.getDefaultInt("menu/page_size")
 * 
 * // 读取方案名称
 * val schemaName = RimeConfigManager.getSchemaString("luna_pinyin", "schema/name")
 * 
 * // 使用配置对象（自动关闭）
 * RimeConfigManager.withConfig("default") { peer ->
 *     val value = RimeConfig.getRimeConfigString(peer, "some/key")
 * }
 * ```
 */
object RimeConfigManager {
    private const val TAG = "RimeConfigManager"

    // ===== 便捷方法：读取默认配置 =====

    /**
     * 从默认配置(default.yaml)读取整数值
     * @param key 配置项路径
     * @return 整数值，读取失败返回null
     */
    fun getDefaultInt(key: String): Int? {
        return getConfigInt("default", key)
    }

    /**
     * 从默认配置(default.yaml)读取字符串值
     * @param key 配置项路径
     * @return 字符串值，读取失败返回null
     */
    fun getDefaultString(key: String): String? {
        return getConfigString("default", key)
    }

    // ===== 便捷方法：读取方案配置 =====

    /**
     * 从指定方案读取整数值
     * @param schemaId 方案ID（如"luna_pinyin"）
     * @param key 配置项路径
     * @return 整数值，读取失败返回null
     */
    fun getSchemaInt(schemaId: String, key: String): Int? {
        val peer = RimeConfig.openRimeSchema(schemaId)
        if (peer == 0L) {
            Log.w(TAG, "无法打开方案配置: $schemaId")
            return null
        }
        return try {
            RimeConfig.getRimeConfigInt(peer, key)
        } finally {
            RimeConfig.closeRimeConfig(peer)
        }
    }

    /**
     * 从指定方案读取字符串值
     * @param schemaId 方案ID（如"luna_pinyin"）
     * @param key 配置项路径
     * @return 字符串值，读取失败返回null
     */
    fun getSchemaString(schemaId: String, key: String): String? {
        val peer = RimeConfig.openRimeSchema(schemaId)
        if (peer == 0L) {
            Log.w(TAG, "无法打开方案配置: $schemaId")
            return null
        }
        return try {
            RimeConfig.getRimeConfigString(peer, key)
        } finally {
            RimeConfig.closeRimeConfig(peer)
        }
    }

    // ===== 通用配置读取 =====

    fun getConfigInt(configId: String, key: String): Int? {
        val peer = RimeConfig.openRimeConfig(configId)
        if (peer == 0L) {
            Log.w(TAG, "无法打开系统配置: $configId")
            return null
        }
        return try {
            RimeConfig.getRimeConfigInt(peer, key)
        } finally {
            RimeConfig.closeRimeConfig(peer)
        }
    }

    fun getConfigString(configId: String, key: String): String? {
        val peer = RimeConfig.openRimeConfig(configId)
        if (peer == 0L) {
            Log.w(TAG, "无法打开系统配置: $configId")
            return null
        }
        return try {
            RimeConfig.getRimeConfigString(peer, key)
        } finally {
            RimeConfig.closeRimeConfig(peer)
        }
    }

    fun getConfigListPaths(configId: String, key: String): Array<String> {
        val peer = RimeConfig.openRimeConfig(configId)
        if (peer == 0L) {
            Log.w(TAG, "无法打开系统配置: $configId")
            return emptyArray()
        }
        return try {
            RimeConfig.getRimeConfigListItemPath(peer, key) ?: emptyArray()
        } finally {
            RimeConfig.closeRimeConfig(peer)
        }
    }

    // ===== 用户配置读取 =====

    fun getUserConfigString(configId: String, key: String): String? {
        val peer = RimeConfig.openRimeUserConfig(configId)
        if (peer == 0L) {
            Log.w(TAG, "无法打开用户配置: $configId")
            return null
        }
        return try {
            RimeConfig.getRimeConfigString(peer, key)
        } finally {
            RimeConfig.closeRimeConfig(peer)
        }
    }

    // ===== 配置写入 =====

    fun setConfigBool(configId: String, key: String, value: Boolean): Boolean {
        val peer = RimeConfig.openRimeConfig(configId)
        if (peer == 0L) {
            Log.w(TAG, "无法打开系统配置: $configId")
            return false
        }
        return try {
            RimeConfig.setRimeConfigBool(peer, key, value)
            true
        } finally {
            RimeConfig.closeRimeConfig(peer)
        }
    }

    // ===== 高阶API =====

    fun <T> withConfig(configId: String, block: (Long) -> T): T? {
        val peer = RimeConfig.openRimeConfig(configId)
        if (peer == 0L) {
            Log.w(TAG, "无法打开系统配置: $configId")
            return null
        }
        return try {
            block(peer)
        } finally {
            RimeConfig.closeRimeConfig(peer)
        }
    }

    fun <T> withSchema(schemaId: String, block: (Long) -> T): T? {
        val peer = RimeConfig.openRimeSchema(schemaId)
        if (peer == 0L) {
            Log.w(TAG, "无法打开方案配置: $schemaId")
            return null
        }
        return try {
            block(peer)
        } finally {
            RimeConfig.closeRimeConfig(peer)
        }
    }

    fun <T> withUserConfig(configId: String, block: (Long) -> T): T? {
        val peer = RimeConfig.openRimeUserConfig(configId)
        if (peer == 0L) {
            Log.w(TAG, "无法打开用户配置: $configId")
            return null
        }
        return try {
            block(peer)
        } finally {
            RimeConfig.closeRimeConfig(peer)
        }
    }
}
