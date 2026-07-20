package com.ziyou.ime.core

import android.util.Log

/**
 * Rime配置文件JNI接口声明
 * 所有native方法对应config.cc中的JNI导出函数
 * 
 * 功能说明：
 * - 打开/关闭配置文件（系统配置、用户配置、方案配置）
 * - 读取配置项（整数、字符串、列表路径）
 * - 设置配置项（布尔值）
 * 
 * 注意：peer参数为C++层RimeConfig结构体的指针，
 *       使用完毕后必须调用closeRimeConfig释放资源
 */
object RimeConfig {
    private const val TAG = "RimeConfig"

    init {
        // 确保native库已加载（依赖RimeNative的init块）
        try {
            System.loadLibrary("rime_jni")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "rime_jni 库可能已由RimeNative加载: ${e.message}")
        }
    }

    // ===== 配置文件打开 =====

    /**
     * 打开系统配置文件
     * @param configId 配置文件ID（不含扩展名，如"default"）
     * @return 配置对象的peer指针，0表示打开失败
     */
    @JvmStatic
    external fun openRimeConfig(configId: String): Long

    /**
     * 打开用户配置文件
     * @param configId 配置文件ID（如"user"）
     * @return 配置对象的peer指针，0表示打开失败
     */
    @JvmStatic
    external fun openRimeUserConfig(configId: String): Long

    /**
     * 打开输入方案配置
     * @param schemaId 方案ID（如"luna_pinyin"）
     * @return 配置对象的peer指针，0表示打开失败
     */
    @JvmStatic
    external fun openRimeSchema(schemaId: String): Long

    // ===== 配置文件关闭 =====

    /**
     * 关闭配置文件并释放资源
     * @param peer 配置对象的指针（由open系列方法返回）
     */
    @JvmStatic
    external fun closeRimeConfig(peer: Long)

    // ===== 配置项读取 =====

    /**
     * 读取整数类型配置项
     * @param peer 配置对象指针
     * @param key 配置项路径（如"menu/page_size"）
     * @return 整数值，键不存在时返回null
     */
    @JvmStatic
    external fun getRimeConfigInt(peer: Long, key: String): Int?

    /**
     * 读取字符串类型配置项
     * @param peer 配置对象指针
     * @param key 配置项路径（如"schema/name"）
     * @return 字符串值，键不存在时返回null
     */
    @JvmStatic
    external fun getRimeConfigString(peer: Long, key: String): String?

    /**
     * 获取列表类型配置项的所有子路径
     * @param peer 配置对象指针
     * @param key 列表配置项路径（如"schema_list"）
     * @return 子路径数组，如["schema_list/@0", "schema_list/@1"]
     */
    @JvmStatic
    external fun getRimeConfigListItemPath(peer: Long, key: String): Array<String>?

    // ===== 配置项写入 =====

    /**
     * 设置布尔类型配置项
     * @param peer 配置对象指针
     * @param key 配置项路径
     * @param value 布尔值
     */
    @JvmStatic
    external fun setRimeConfigBool(peer: Long, key: String, value: Boolean)
}
