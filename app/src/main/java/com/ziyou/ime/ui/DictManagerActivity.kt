package com.ziyou.ime.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ziyou.ime.dict.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 扩展词库管理页面（Jetpack Compose）
 * 提供词库浏览、下载、启用/禁用、卸载、更新等功能
 */
class DictManagerActivity : ComponentActivity() {

    private val viewModel: DictManagerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 标题栏复用 View 版 [TitleBarView]（与设置页等共用同一实现，样式天然一致），
        // 内容区仍为 Compose，经 ComposeView 挂在标题栏下方
        setContentViewWithTitleBar("扩展词库", ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    DictManagerScreen(viewModel = viewModel)
                }
            }
        })

        // 监听部署状态，给出 Toast 提示
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.deployState.collectLatest { state ->
                    when (state) {
                        is DeployState.Done -> {
                            Toast.makeText(this@DictManagerActivity, "词库已生效", Toast.LENGTH_SHORT).show()
                            viewModel.resetDeployState()
                        }
                        is DeployState.Failed -> {
                            Toast.makeText(this@DictManagerActivity, "部署失败: ${state.message}", Toast.LENGTH_LONG).show()
                            viewModel.resetDeployState()
                        }
                        else -> {}
                    }
                }
            }
        }
    }
}

@Composable
private fun DictManagerScreen(viewModel: DictManagerViewModel) {
    val catalogState by viewModel.catalogState.collectAsState()
    val installedDicts by viewModel.installedDicts.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()
    val deployState by viewModel.deployState.collectAsState()
    val updatableIds by viewModel.updatableIds.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val showInstalledOnly by viewModel.showInstalledOnly.collectAsState()
    val previewState by viewModel.previewState.collectAsState()

    // 词库预览弹窗
    DictPreviewDialog(
        previewState = previewState,
        onDismiss = { viewModel.dismissPreview() }
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // 分类筛选 TabRow
        CategoryTabRow(
            selectedCategory = selectedCategory,
            showInstalledOnly = showInstalledOnly,
            onCategorySelected = { viewModel.selectCategory(it) },
            onShowInstalled = { viewModel.setShowInstalledOnly(true) }
        )

        // 部署状态提示
        if (deployState is DeployState.Deploying) {
            DeployingBanner()
        }

        // 内容区域
        when {
            showInstalledOnly -> {
                InstalledDictsList(
                    installedDicts = installedDicts,
                    catalogState = catalogState,
                    updatableIds = updatableIds,
                    downloadState = downloadState,
                    onToggleEnabled = { id, enabled -> viewModel.toggleEnabled(id, enabled) },
                    onUninstall = { id -> viewModel.uninstallDict(id) },
                    onUpdate = { info -> viewModel.updateDict(info) },
                    onPreview = { info -> viewModel.loadPreview(info) }
                )
            }
            else -> {
                when (val state = catalogState) {
                    is DictManagerViewModel.CatalogState.Loading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    is DictManagerViewModel.CatalogState.Error -> {
                        ErrorContent(
                            message = state.message,
                            onRetry = { viewModel.refreshCatalog() }
                        )
                    }
                    is DictManagerViewModel.CatalogState.Success -> {
                        val filteredDicts = viewModel.getFilteredDicts()
                        CatalogDictsList(
                            dicts = filteredDicts,
                            installedDicts = installedDicts,
                            updatableIds = updatableIds,
                            downloadState = downloadState,
                            onInstall = { viewModel.installDict(it) },
                            onUpdate = { viewModel.updateDict(it) },
                            onToggleEnabled = { id, enabled -> viewModel.toggleEnabled(id, enabled) },
                            onUninstall = { viewModel.uninstallDict(it) },
                            onPreview = { viewModel.loadPreview(it) }
                        )
                    }
                }
            }
        }

        // 底部下载进度条
        if (downloadState is DownloadState.Downloading) {
            val state = downloadState as DownloadState.Downloading
            DownloadProgressBar(state)
        }
    }
}

@Composable
private fun CategoryTabRow(
    selectedCategory: DictCategory?,
    showInstalledOnly: Boolean,
    onCategorySelected: (DictCategory?) -> Unit,
    onShowInstalled: () -> Unit
) {
    val tabs = listOf("全部") + DictCategory.entries.map { it.displayName } + "已安装"
    val selectedIndex = when {
        showInstalledOnly -> tabs.size - 1
        selectedCategory == null -> 0
        else -> DictCategory.entries.indexOf(selectedCategory) + 1
    }

    PrimaryScrollableTabRow(
        selectedTabIndex = selectedIndex,
        edgePadding = 12.dp,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        tabs.forEachIndexed { index, title ->
            Tab(
                selected = selectedIndex == index,
                onClick = {
                    when (index) {
                        0 -> onCategorySelected(null)
                        tabs.size - 1 -> onShowInstalled()
                        else -> onCategorySelected(DictCategory.entries[index - 1])
                    }
                },
                text = { Text(title, fontSize = 13.sp) }
            )
        }
    }
}

@Composable
private fun CatalogDictsList(
    dicts: List<RemoteDictInfo>,
    installedDicts: List<InstalledDictInfo>,
    updatableIds: Set<String>,
    downloadState: DownloadState,
    onInstall: (RemoteDictInfo) -> Unit,
    onUpdate: (RemoteDictInfo) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onUninstall: (String) -> Unit,
    onPreview: (RemoteDictInfo) -> Unit
) {
    if (dicts.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无可用词库", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(dicts, key = { it.id }) { dict ->
            val installed = installedDicts.firstOrNull { it.id == dict.id }
            val isDownloading = downloadState is DownloadState.Downloading &&
                    (downloadState as DownloadState.Downloading).dictId == dict.id
            val hasUpdate = dict.id in updatableIds

            DictCard(
                dict = dict,
                installedInfo = installed,
                isDownloading = isDownloading,
                hasUpdate = hasUpdate,
                onInstall = { onInstall(dict) },
                onUpdate = { onUpdate(dict) },
                onToggleEnabled = onToggleEnabled,
                onUninstall = onUninstall,
                onPreview = { onPreview(dict) }
            )
        }
    }
}

@Composable
private fun InstalledDictsList(
    installedDicts: List<InstalledDictInfo>,
    catalogState: DictManagerViewModel.CatalogState,
    updatableIds: Set<String>,
    downloadState: DownloadState,
    onToggleEnabled: (String, Boolean) -> Unit,
    onUninstall: (String) -> Unit,
    onUpdate: (RemoteDictInfo) -> Unit,
    onPreview: (RemoteDictInfo) -> Unit
) {
    if (installedDicts.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("尚未安装任何扩展词库", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val catalog = (catalogState as? DictManagerViewModel.CatalogState.Success)?.catalog

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(installedDicts, key = { it.id }) { installed ->
            val remoteInfo = catalog?.dictionaries?.firstOrNull { it.id == installed.id }
            val isDownloading = downloadState is DownloadState.Downloading &&
                    (downloadState as DownloadState.Downloading).dictId == installed.id
            val hasUpdate = installed.id in updatableIds

            if (remoteInfo != null) {
                DictCard(
                    dict = remoteInfo,
                    installedInfo = installed,
                    isDownloading = isDownloading,
                    hasUpdate = hasUpdate,
                    onInstall = {},
                    onUpdate = { onUpdate(remoteInfo) },
                    onToggleEnabled = onToggleEnabled,
                    onUninstall = onUninstall,
                    onPreview = { onPreview(remoteInfo) }
                )
            } else {
                // 本地有但远程目录中没有（可能已下架）
                InstalledOnlyCard(
                    installed = installed,
                    onToggleEnabled = onToggleEnabled,
                    onUninstall = onUninstall
                )
            }
        }
    }
}

@Composable
private fun DictCard(
    dict: RemoteDictInfo,
    installedInfo: InstalledDictInfo?,
    isDownloading: Boolean,
    hasUpdate: Boolean,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onUninstall: (String) -> Unit,
    onPreview: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPreview() },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 第一行：名称 + 分类标签
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dict.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.width(8.dp))
                    AssistChip(
                        onClick = {},
                        label = { Text(dict.dictCategory.displayName, fontSize = 11.sp) },
                        modifier = Modifier.height(24.dp)
                    )
                }
                // 操作区域
                when {
                    isDownloading -> {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                    installedInfo == null -> {
                        FilledTonalButton(onClick = onInstall) {
                            Text("下载", fontSize = 13.sp)
                        }
                    }
                    hasUpdate -> {
                        Button(onClick = onUpdate) {
                            Text("更新", fontSize = 13.sp)
                        }
                    }
                    else -> {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Text("⋮", fontSize = 18.sp)
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("卸载") },
                                    onClick = {
                                        showMenu = false
                                        onUninstall(dict.id)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 描述
            Text(
                text = dict.description,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )

            // 底部信息行：大小 + 版本 + 启用开关
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${dict.sizeDisplay} · v${dict.version}" +
                            if (dict.author.isNotEmpty()) " · ${dict.author}" else "",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline
                )

                // 已安装时显示启用开关
                if (installedInfo != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (installedInfo.enabled) "已启用" else "已禁用",
                            fontSize = 12.sp,
                            color = if (installedInfo.enabled)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.width(4.dp))
                        Switch(
                            checked = installedInfo.enabled,
                            onCheckedChange = { onToggleEnabled(dict.id, it) },
                            modifier = Modifier.height(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledOnlyCard(
    installed: InstalledDictInfo,
    onToggleEnabled: (String, Boolean) -> Unit,
    onUninstall: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(installed.id, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    "v${installed.version}（已从目录下架）",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = installed.enabled,
                    onCheckedChange = { onToggleEnabled(installed.id, it) },
                    modifier = Modifier.height(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { onUninstall(installed.id) }) {
                    Text("卸载", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun DeployingBanner() {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("正在重新部署引擎，请稍候...", fontSize = 13.sp)
        }
    }
}

@Composable
private fun DownloadProgressBar(state: DownloadState.Downloading) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "正在下载: ${state.dictId}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) {
                Text("重试")
            }
        }
    }
}

// ===== 词库预览弹窗 =====

@Composable
private fun DictPreviewDialog(
    previewState: PreviewState,
    onDismiss: () -> Unit
) {
    when (previewState) {
        is PreviewState.Idle -> { /* 不显示 */ }

        is PreviewState.Loading -> {
            Dialog(onDismissRequest = onDismiss) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("正在加载词库预览...", fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        is PreviewState.Error -> {
            Dialog(onDismissRequest = onDismiss) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = previewState.message,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(16.dp))
                        TextButton(onClick = onDismiss) {
                            Text("关闭")
                        }
                    }
                }
            }
        }

        is PreviewState.Success -> {
            val preview = previewState.preview
            val info = preview.dictInfo

            Dialog(
                onDismissRequest = onDismiss,
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .fillMaxHeight(0.8f)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 标题栏
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = info.name,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = info.dictCategory.displayName,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    )
                                }
                                TextButton(onClick = onDismiss) {
                                    Text("关闭")
                                }
                            }
                        }

                        // 内容区域（可滚动）
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp)
                        ) {
                            // 基本信息区
                            Text(
                                text = "基本信息",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(8.dp))

                            if (info.description.isNotEmpty()) {
                                Text(
                                    text = info.description,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                            }

                            // 元数据网格
                            InfoRow("版本", info.version)
                            InfoRow("大小", info.sizeDisplay)
                            if (info.author.isNotEmpty()) {
                                InfoRow("作者", info.author)
                            }
                            InfoRow("词条数", "约 ${preview.totalEntriesHint} 条")

                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                            // 词条预览区
                            Text(
                                text = "词条预览（前 ${preview.entries.size} 条）",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(8.dp))

                            if (preview.entries.isEmpty()) {
                                Text(
                                    text = "暂无词条数据",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            } else {
                                // 表头
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "词语",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "编码",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.weight(1.5f)
                                    )
                                }

                                HorizontalDivider()

                                // 词条列表
                                preview.entries.forEach { entry ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = entry.word,
                                            fontSize = 13.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = entry.code,
                                            fontSize = 13.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1.5f)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = "$label：",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(64.dp)
        )
        Text(
            text = value,
            fontSize = 13.sp
        )
    }
}
