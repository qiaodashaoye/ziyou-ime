package com.ziyou.ime.core.skill

/**
 * 技能版本号比较（纯逻辑）。
 *
 * 版本号为数字点分格式（经 [SkillManifestValidator] 校验），按段数值比较，
 * 缺段视为 0（1.0 == 1.0.0）。用于安装流水线的同 id 升级判定。
 */
object SkillVersionComparator {

    /**
     * 比较两个版本号。
     * @return 负数 = a < b，0 = 相等，正数 = a > b
     */
    fun compare(a: String, b: String): Int {
        val segmentsA = a.split('.').map { it.toLongOrNull() ?: 0L }
        val segmentsB = b.split('.').map { it.toLongOrNull() ?: 0L }
        val size = maxOf(segmentsA.size, segmentsB.size)
        for (i in 0 until size) {
            val va = segmentsA.getOrElse(i) { 0L }
            val vb = segmentsB.getOrElse(i) { 0L }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    /** 新版本是否可覆盖安装旧版本（严格更高才允许，防降级替换攻击）。 */
    fun isUpgrade(installed: String, incoming: String): Boolean = compare(incoming, installed) > 0
}
