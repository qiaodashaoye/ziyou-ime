package com.ziyou.ime.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ziyou.ime.dict.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 扩展词库管理 ViewModel
 * 管理词库目录加载、下载进度、安装状态等 UI 状态
 */
class DictManagerViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DictManagerVM"
    }

    /** 远程词库目录加载状态 */
    sealed class CatalogState {
        data object Loading : CatalogState()
        data class Success(val catalog: DictCatalog) : CatalogState()
        data class Error(val message: String) : CatalogState()
    }

    private val _catalogState = MutableStateFlow<CatalogState>(CatalogState.Loading)
    val catalogState: StateFlow<CatalogState> = _catalogState.asStateFlow()

    private val _installedDicts = MutableStateFlow<List<InstalledDictInfo>>(emptyList())
    val installedDicts: StateFlow<List<InstalledDictInfo>> = _installedDicts.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _deployState = MutableStateFlow<DeployState>(DeployState.Idle)
    val deployState: StateFlow<DeployState> = _deployState.asStateFlow()

    /** 可更新的词库 ID 集合 */
    private val _updatableIds = MutableStateFlow<Set<String>>(emptySet())
    val updatableIds: StateFlow<Set<String>> = _updatableIds.asStateFlow()

    /** 当前选中的分类筛选（null = 全部） */
    private val _selectedCategory = MutableStateFlow<DictCategory?>(null)
    val selectedCategory: StateFlow<DictCategory?> = _selectedCategory.asStateFlow()

    /** 是否只看已安装 */
    private val _showInstalledOnly = MutableStateFlow(false)
    val showInstalledOnly: StateFlow<Boolean> = _showInstalledOnly.asStateFlow()

    /** 词库预览状态 */
    private val _previewState = MutableStateFlow<PreviewState>(PreviewState.Idle)
    val previewState: StateFlow<PreviewState> = _previewState.asStateFlow()

    init {
        loadInstalledDicts()
        refreshCatalog()
    }

    /** 加载本地已安装词库 */
    private fun loadInstalledDicts() {
        val context = getApplication<Application>()
        _installedDicts.value = DictManager.getInstalledDicts(context)
    }

    /** 刷新远程词库目录 */
    fun refreshCatalog() {
        viewModelScope.launch {
            _catalogState.value = CatalogState.Loading
            val catalog = DictDownloader.fetchCatalog()
            if (catalog != null) {
                _catalogState.value = CatalogState.Success(catalog)
                // 检查更新
                val context = getApplication<Application>()
                _updatableIds.value = DictManager.checkUpdates(context, catalog).toSet()
            } else {
                _catalogState.value = CatalogState.Error("无法连接词库服务器，请检查网络")
            }
        }
    }

    /** 设置分类筛选 */
    fun selectCategory(category: DictCategory?) {
        _selectedCategory.value = category
        _showInstalledOnly.value = false
    }

    /** 设置只看已安装 */
    fun setShowInstalledOnly(show: Boolean) {
        _showInstalledOnly.value = show
        if (show) _selectedCategory.value = null
    }

    /** 获取筛选后的词库列表 */
    fun getFilteredDicts(): List<RemoteDictInfo> {
        val state = _catalogState.value
        if (state !is CatalogState.Success) return emptyList()

        val category = _selectedCategory.value
        return if (category != null) {
            state.catalog.dictionaries.filter { it.dictCategory == category }
        } else {
            state.catalog.dictionaries
        }
    }

    /** 判断词库是否已安装 */
    fun isInstalled(dictId: String): Boolean {
        return _installedDicts.value.any { it.id == dictId }
    }

    /** 获取已安装词库信息 */
    fun getInstalledInfo(dictId: String): InstalledDictInfo? {
        return _installedDicts.value.firstOrNull { it.id == dictId }
    }

    /** 下载并安装词库 */
    fun installDict(info: RemoteDictInfo) {
        viewModelScope.launch {
            _downloadState.value = DownloadState.Downloading(info.id, 0f, 0, info.size)

            val context = getApplication<Application>()
            val success = DictManager.installDict(context, info) { downloaded, total ->
                val progress = if (total > 0) downloaded.toFloat() / total else 0f
                _downloadState.value = DownloadState.Downloading(info.id, progress, downloaded, total)
            }

            if (success) {
                _downloadState.value = DownloadState.Success(info.id)
                loadInstalledDicts()
                // 触发重部署
                redeploy()
            } else {
                _downloadState.value = DownloadState.Error(info.id, "下载失败，请重试")
            }
        }
    }

    /** 卸载词库 */
    fun uninstallDict(dictId: String) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            DictManager.uninstallDict(context, dictId)
            loadInstalledDicts()
            redeploy()
        }
    }

    /** 切换词库启用状态 */
    fun toggleEnabled(dictId: String, enabled: Boolean) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            DictManager.setEnabled(context, dictId, enabled)
            loadInstalledDicts()
            redeploy()
        }
    }

    /** 更新词库 */
    fun updateDict(info: RemoteDictInfo) {
        installDict(info) // 更新等同于重新安装
    }

    /** 触发 RIME 重部署 */
    private suspend fun redeploy() {
        _deployState.value = DeployState.Deploying
        try {
            val context = getApplication<Application>()
            // 通过 RimeSession 重新部署引擎
            com.ziyou.ime.daemon.RimeSession.redeploy(context)
            _deployState.value = DeployState.Done
            Log.i(TAG, "RIME 重部署完成")
        } catch (e: Exception) {
            Log.e(TAG, "RIME 重部署失败: ${e.message}", e)
            _deployState.value = DeployState.Failed(e.message ?: "部署失败")
        }
    }

    /** 重置下载状态 */
    fun resetDownloadState() {
        _downloadState.value = DownloadState.Idle
    }

    /** 重置部署状态 */
    fun resetDeployState() {
        _deployState.value = DeployState.Idle
    }

    // ===== 词库预览 =====

    /**
     * 加载词库预览
     * 已安装的词库优先读取本地文件，未安装的从远程下载预览
     */
    fun loadPreview(info: RemoteDictInfo) {
        viewModelScope.launch {
            _previewState.value = PreviewState.Loading(info.id)

            val context = getApplication<Application>()
            val preview = if (isInstalled(info.id)) {
                // 已安装：读取本地文件
                DictManager.readLocalDictPreview(context, info.id, info)
            } else {
                // 未安装：从远程下载预览
                DictDownloader.fetchDictPreview(info)
            }

            _previewState.value = if (preview != null) {
                PreviewState.Success(preview)
            } else {
                PreviewState.Error(info.id, "无法加载词库预览")
            }
        }
    }

    /** 关闭预览 */
    fun dismissPreview() {
        _previewState.value = PreviewState.Idle
    }
}
