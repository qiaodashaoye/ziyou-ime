package com.ziyou.ime.skin

import android.content.Context
import android.util.Log
import com.ziyou.ime.core.skin.SkinDefaults
import com.ziyou.ime.core.skin.SkinPackConstraints
import com.ziyou.ime.core.skin.SkinSpec
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * `.zyskin` 皮肤包装载器（导入 / 卸载）。
 *
 * 安装流水线（与技能域 SkillPackageInstaller 同一纪律，皮肤无权限概念故单段式）：
 * 包体落临时文件（边拷边卡体积上限）→ zip 全量校验（条目数 / Zip Slip /
 * 扩展名白名单 / skin.json 解析校验 / 引用资源存在性）→ 解压 staging（逐条目
 * 卡单文件与总量上限）→ 备份-替换原子就位 → 登记索引。
 * 任一环节失败整包拒绝，返回 [Result] 携带可展示的错误明细。
 */
object SkinPackLoader {
    private const val TAG = "SkinPackLoader"

    /** 安装结果。 */
    sealed class Result {
        data class Success(val info: SkinInfo, val upgraded: Boolean) : Result()

        /** 包内容非法（校验错误明细，可直接展示给用户） */
        data class Invalid(val errors: List<String>) : Result()

        /** 环境性失败（IO / 磁盘空间等） */
        data class Failed(val cause: String) : Result()
    }

    /**
     * 从输入流导入 `.zyskin` 皮肤包（阻塞 IO，调用方在 Dispatchers.IO 执行）。
     * 本方法负责关闭 [source]。
     */
    fun install(context: Context, source: InputStream): Result {
        val tempFile = File(tempRoot(context).apply { mkdirs() },
            "import_${System.currentTimeMillis()}.zyskin")
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
                        if (total > SkinPackConstraints.MAX_PACKAGE_BYTES) {
                            return Result.Invalid(listOf("皮肤包超过 " +
                                "${SkinPackConstraints.MAX_PACKAGE_BYTES / 1024 / 1024}MB 上限"))
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }

            val spec = when (val validated = validateZip(tempFile)) {
                is ValidateResult.Ok -> validated.spec
                is ValidateResult.Bad -> return Result.Invalid(validated.errors)
            }

            val upgraded = File(SkinRepository.skinsRoot(context), spec.meta.id).exists()
            extractAndCommit(context, tempFile, spec)
            SkinRepository.addToIndex(context, spec)
            SkinRepository.evictSpec(spec.meta.id)
            SkinAssetCache.evict(spec.meta.id)
            // 重装的正是当前皮肤 → 快照失效重建
            if (SkinRepository.getCurrentSkinId(context) == spec.meta.id) {
                SkinManager.invalidate(context)
            }
            Log.i(TAG, "皮肤安装完成: ${spec.meta.id} v${spec.meta.version}")
            val info = SkinRepository.findInstalled(context, spec.meta.id)
                ?: return Result.Failed("安装后索引读取失败")
            return Result.Success(info, upgraded)
        } catch (e: IllegalArgumentException) {
            return Result.Invalid(listOf(e.message ?: "皮肤包非法"))
        } catch (e: Exception) {
            Log.e(TAG, "皮肤安装失败: ${e.message}", e)
            return Result.Failed(e.message ?: "未知错误")
        } finally {
            tempFile.delete()
        }
    }

    /**
     * 卸载导入皮肤（内置皮肤不可卸载）。
     * 同时清理索引、用户覆盖与资源缓存；卸载的是当前皮肤时回退默认皮肤。
     */
    fun uninstall(context: Context, skinId: String): Boolean {
        if (SkinDefaults.isBuiltin(skinId)) return false
        val dir = File(SkinRepository.skinsRoot(context), skinId)
        val removed = !dir.exists() || dir.deleteRecursively()
        if (removed) {
            SkinRepository.removeFromIndex(context, skinId)
            SkinRepository.clearOverride(context, skinId)
            SkinRepository.evictSpec(skinId)
            SkinAssetCache.evict(skinId)
            if (SkinRepository.getCurrentSkinId(context) == skinId) {
                SkinRepository.setCurrentSkinId(context, SkinDefaults.DEFAULT_SKIN_ID)
                SkinManager.invalidate(context)
            }
            Log.i(TAG, "皮肤已卸载: $skinId")
        }
        return removed
    }

    // ===== 内部 =====

    internal sealed class ValidateResult {
        data class Ok(val spec: SkinSpec) : ValidateResult()
        data class Bad(val errors: List<String>) : ValidateResult()
    }

    /**
     * zip 结构校验：条目数、路径安全、扩展名白名单、skin.json、引用资源存在性。
     * 无 Context 依赖，对模块内开放供 JVM 单测直接验证。
     */
    internal fun validateZip(file: File): ValidateResult {
        val errors = mutableListOf<String>()
        ZipFile(file).use { zip ->
            if (zip.size() > SkinPackConstraints.MAX_ENTRIES) {
                return ValidateResult.Bad(
                    listOf("皮肤包条目数超限（上限 ${SkinPackConstraints.MAX_ENTRIES}）"))
            }
            var skinJson: String? = null
            val entryNames = mutableSetOf<String>()
            var totalBytes = 0L
            for (entry in zip.entries()) {
                val name = entry.name.removeSuffix("/")
                if (entry.isDirectory) continue
                if (!SkinPackConstraints.isAllowedEntry(name)) {
                    errors += "非法包内条目: ${entry.name}"
                    continue
                }
                if (entry.size > SkinPackConstraints.MAX_ENTRY_BYTES) {
                    errors += "条目超过单文件上限: $name"
                }
                totalBytes += entry.size.coerceAtLeast(0)
                entryNames += name
                if (name == SkinPackConstraints.SKIN_JSON) {
                    skinJson = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                }
            }
            if (totalBytes > SkinPackConstraints.MAX_TOTAL_BYTES) {
                errors += "皮肤包解压总量超限（上限 ${SkinPackConstraints.MAX_TOTAL_BYTES / 1024 / 1024}MB）"
            }
            if (errors.isNotEmpty()) return ValidateResult.Bad(errors)

            val json = skinJson
                ?: return ValidateResult.Bad(listOf("皮肤包缺少 ${SkinPackConstraints.SKIN_JSON}"))
            val spec = try {
                SkinSpecCodec.decodeSpec(json)
            } catch (e: IllegalArgumentException) {
                return ValidateResult.Bad(listOf(e.message ?: "skin.json 非法"))
            }

            // id 约束：不可冒充内置皮肤
            if (SkinDefaults.isBuiltin(spec.meta.id)) {
                errors += "皮肤 id 与内置皮肤冲突: ${spec.meta.id}"
            }
            // 引用资源必须真实存在于包内
            for (path in listOfNotNull(
                spec.layer.background?.image,
                spec.layer.background?.imageDark,
                spec.layer.typography?.fontFamily
            )) {
                if (path !in entryNames) errors += "引用的资源不存在于包内: $path"
            }
            return if (errors.isEmpty()) ValidateResult.Ok(spec) else ValidateResult.Bad(errors)
        }
    }

    /**
     * 解压到 staging → 备份-替换原子就位（与 SkillPackageInstaller 同一回滚纪律：
     * 旧版本移至备份位而非直接删除，就位失败恢复旧版本）。
     */
    private fun extractAndCommit(context: Context, packageFile: File, spec: SkinSpec) {
        val root = SkinRepository.skinsRoot(context)
        val installDir = File(root, spec.meta.id)
        val stagingDir = File(root, ".staging_${spec.meta.id}")
        val backupDir = File(root, ".backup_${spec.meta.id}")
        try {
            root.mkdirs()
            val required = packageFile.length() * 4 + 1024 * 1024
            if (root.usableSpace in 1 until required) {
                throw IllegalStateException("存储空间不足，无法安装皮肤")
            }

            stagingDir.deleteRecursively()
            stagingDir.mkdirs()
            var totalBytes = 0L
            ZipFile(packageFile).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    val name = entry.name.removeSuffix("/")
                    // validateZip 已校验过，此处为纵深防御再校验一次
                    if (!SkinPackConstraints.isAllowedEntry(name)) {
                        throw IllegalArgumentException("非法包内条目: ${entry.name}")
                    }
                    val target = File(stagingDir, name)
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output ->
                            // 解压期逐字节卡上限（entry.size 可被伪造，不可信）
                            val buffer = ByteArray(8 * 1024)
                            var entryBytes = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                entryBytes += read
                                totalBytes += read
                                if (entryBytes > SkinPackConstraints.MAX_ENTRY_BYTES ||
                                    totalBytes > SkinPackConstraints.MAX_TOTAL_BYTES
                                ) {
                                    throw IllegalArgumentException("皮肤包解压体积超限")
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
            }

            backupDir.deleteRecursively()
            if (installDir.exists() && !installDir.renameTo(backupDir)) {
                throw IllegalStateException("无法移出旧版本目录，安装中止（旧版本未受影响）")
            }
            if (!stagingDir.renameTo(installDir)) {
                val rolledBack = backupDir.renameTo(installDir)
                throw IllegalStateException(
                    if (rolledBack) "安装目录写入失败，已恢复旧版本"
                    else "安装目录写入失败，旧版本保留在 ${backupDir.name}"
                )
            }
            backupDir.deleteRecursively()
        } finally {
            // 注意：不在此删 backupDir——回滚失败时它是旧版本的唯一副本
            stagingDir.deleteRecursively()
        }
    }

    private fun tempRoot(context: Context): File = File(context.cacheDir, "skin_import")
}
