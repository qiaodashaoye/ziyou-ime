package com.ziyou.ime.util

/**
 * 应用版本号比较工具（纯逻辑，无 Android 依赖）
 *
 * 供应用内更新的版本对比使用：当远端未提供数字构建号（versionCode 对应值）时，
 * 回退为按 versionName 逐段比较。
 *
 * 比较规则：
 * - 以 "." 分段，从左到右逐段比较，缺失段视为 "0"（即 "1.2" == "1.2.0"）；
 * - 两段均为纯数字时按数值比较（忽略前导零，超长数字退化为字符串比较避免溢出）；
 * - 一段为数字另一段非数字时，数字段更大（发布版 > 预发布标记，如 "1.0" > "1.0-beta"）；
 * - 两段均非数字时按字典序比较；
 * - 空白版本号视为 "0"。
 */
object AppVersionUtils {

    /** 纯数字段允许的最大位数，超出则退化为字典序比较（避免 Long 溢出） */
    private const val MAX_NUMERIC_SEGMENT_LENGTH = 18

    /**
     * 比较两个版本号字符串。
     * @return 负数表示 [a] 低于 [b]，0 表示相等，正数表示 [a] 高于 [b]
     */
    fun compareVersionNames(a: String, b: String): Int {
        val segmentsA = normalize(a)
        val segmentsB = normalize(b)
        val maxLen = maxOf(segmentsA.size, segmentsB.size)
        for (i in 0 until maxLen) {
            val segA = segmentsA.getOrElse(i) { "0" }
            val segB = segmentsB.getOrElse(i) { "0" }
            val cmp = compareSegments(segA, segB)
            if (cmp != 0) return cmp
        }
        return 0
    }

    private fun normalize(version: String): List<String> {
        val trimmed = version.trim()
        if (trimmed.isEmpty()) return listOf("0")
        return trimmed.split(".").map { it.trim() }
    }

    private fun compareSegments(a: String, b: String): Int {
        val aNumeric = isNumeric(a)
        val bNumeric = isNumeric(b)
        return when {
            aNumeric && bNumeric -> a.toLong().compareTo(b.toLong())
            aNumeric -> 1          // 数字段 > 非数字段（发布版高于预发布标记）
            bNumeric -> -1
            else -> a.compareTo(b)
        }
    }

    private fun isNumeric(segment: String): Boolean {
        if (segment.isEmpty() || segment.length > MAX_NUMERIC_SEGMENT_LENGTH) return false
        return segment.all { it.isDigit() }
    }
}
