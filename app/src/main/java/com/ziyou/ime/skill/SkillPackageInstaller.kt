package com.ziyou.ime.skill

import android.content.Context
import android.util.Log
import com.ziyou.ime.core.skill.SkillManifest
import com.ziyou.ime.core.skill.SkillVersionComparator
import com.ziyou.ime.core.skill.ZipEntryValidator
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * `.skill` 包安装器（Phase 2）。
 *
 * 两段式安装，保证「权限确认在落盘之前」：
 * 1. [inspect]：包体落临时文件 + 全量校验（大小/条目数/Zip Slip/manifest/冲突），
 *    返回 [PendingInstall] 供 UI 展示权限确认弹窗；
 * 2. 用户确认 → [commit] 解压到 `files/skills/<id>/`（staging 目录原子替换）；
 *    用户拒绝 → [abort] 清理临时文件。
 *
 * 校验失败统一抛 [IllegalArgumentException]，message 可直接展示给用户。
 */
object SkillPackageInstaller {
    private const val TAG = "SkillPackageInstaller"

    private const val MANIFEST_FILE = "manifest.json"

    /** 待确认的安装事务（inspect 产物）。 */
    class PendingInstall internal constructor(
        val manifest: SkillManifest,
        /** 同 id 已安装的旧版本号（升级场景非空） */
        val upgradeFromVersion: String?,
        internal val packageFile: File
    )

    /**
     * 第一阶段：读入包体并全量校验（不落安装目录）。
     * @param source `.skill` 包输入流（SAF / URL 下载共用），本方法负责关闭
     */
    fun inspect(context: Context, source: InputStream): PendingInstall {
        val tempFile = File(tempRoot(context).apply { mkdirs() },
            "import_${System.currentTimeMillis()}.skill")
        try {
            // 拷贝到临时文件，边拷边卡包体上限
            source.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > ZipEntryValidator.MAX_PACKAGE_BYTES) {
                            throw IllegalArgumentException("技能包超过 5MB 上限")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }

            val manifest = validateZip(tempFile)

            // 冲突判定：内置技能 id 不可覆盖；已安装同 id 仅允许升级
            val existing = SkillManager.listSkills(context)
                .firstOrNull { it.manifest.id == manifest.id }
            var upgradeFrom: String? = null
            if (existing != null) {
                if (existing.builtin) {
                    throw IllegalArgumentException("技能 id 与内置技能冲突: ${manifest.id}")
                }
                if (!SkillVersionComparator.isUpgrade(existing.manifest.version, manifest.version)) {
                    throw IllegalArgumentException(
                        "已安装 ${existing.manifest.version}，导入版本 ${manifest.version} 不高于现有版本")
                }
                upgradeFrom = existing.manifest.version
            }
            return PendingInstall(manifest, upgradeFrom, tempFile)
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    /** 第二阶段：用户确认后解压安装（staging 目录就绪后原子替换旧目录）。 */
    fun commit(context: Context, pending: PendingInstall) {
        val installDir = File(SkillManager.installRoot(context), pending.manifest.id)
        val stagingDir = File(SkillManager.installRoot(context), ".staging_${pending.manifest.id}")
        try {
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()
            ZipFile(pending.packageFile).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    // inspect 已校验过路径，此处为纵深防御再校验一次
                    if (!ZipEntryValidator.isSafeRelativePath(entry.name)) {
                        throw IllegalArgumentException("非法包内路径: ${entry.name}")
                    }
                    val target = File(stagingDir, entry.name)
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            installDir.deleteRecursively()
            if (!stagingDir.renameTo(installDir)) {
                throw IllegalStateException("安装目录写入失败")
            }
            Log.i(TAG, "技能安装完成: ${pending.manifest.id} v${pending.manifest.version}")
        } finally {
            stagingDir.deleteRecursively()
            pending.packageFile.delete()
        }
    }

    /** 用户取消安装：清理临时包体。 */
    fun abort(pending: PendingInstall) {
        pending.packageFile.delete()
    }

    /** 卸载已安装技能（内置技能不可卸载），同时清理其 storage 数据。 */
    fun uninstall(context: Context, skill: SkillInfo): Boolean {
        if (skill.builtin || skill.installDir == null) return false
        val removed = skill.installDir.deleteRecursively()
        if (removed) {
            SkillRuntime.deleteStorage(context, skill.manifest.id)
            Log.i(TAG, "技能已卸载: ${skill.manifest.id}")
        }
        return removed
    }

    // ===== 内部 =====

    /** zip 结构校验：条目数、Zip Slip、manifest 解析、入口文件存在性。 */
    private fun validateZip(file: File): SkillManifest {
        ZipFile(file).use { zip ->
            if (zip.size() > ZipEntryValidator.MAX_ENTRIES) {
                throw IllegalArgumentException("技能包条目数超限（上限 ${ZipEntryValidator.MAX_ENTRIES}）")
            }
            var manifestJson: String? = null
            val entryNames = mutableSetOf<String>()
            for (entry in zip.entries()) {
                val name = entry.name.removeSuffix("/")
                if (!ZipEntryValidator.isSafeRelativePath(name)) {
                    throw IllegalArgumentException("非法包内路径: ${entry.name}")
                }
                if (entry.isDirectory) continue
                entryNames += name
                if (name == MANIFEST_FILE) {
                    manifestJson = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                }
            }
            val json = manifestJson
                ?: throw IllegalArgumentException("技能包缺少 $MANIFEST_FILE")
            val manifest = SkillManifestParser.parse(json)
            if (manifest.entry !in entryNames) {
                throw IllegalArgumentException("入口文件不存在于包内: ${manifest.entry}")
            }
            return manifest
        }
    }

    private fun tempRoot(context: Context): File = File(context.cacheDir, "skill_import")
}
