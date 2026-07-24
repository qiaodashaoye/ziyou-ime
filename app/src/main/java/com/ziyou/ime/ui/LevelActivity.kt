package com.ziyou.ime.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ziyou.ime.core.level.LevelEngine
import com.ziyou.ime.level.LevelRepository
import com.ziyou.ime.level.LevelState

/**
 * 等级体系详情页面（Jetpack Compose）。
 *
 * 入口位于 [SettingsActivity]「成长」分区，展示：
 * - 当前等级徽章与升级进度条（Lv.X 名称 cur/next）
 * - 今日 / 累计统计（脱敏聚合计数）
 * - 1–10 级等级路线图与各等级解锁权益
 *
 * 数据来源 [LevelRepository]，纯本地 SharedPreferences，与 IME 服务同进程共享。
 */
class LevelActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val state = LevelRepository.load(this)

        setContent {
            MaterialTheme {
                LevelScreen(state = state, onBack = { finish() })
            }
        }
    }
}

// ===== 等级路线图数据 =====

/** 等级路线图条目：等级、门槛、解锁权益描述。 */
private data class LevelRoadmapItem(
    val level: Int,
    val threshold: Long,
    val benefit: String
)

/** 1–10 级路线图（权益仅"锦上添花"，不锁核心输入能力，详见可行性方案第 4.2 节）。 */
private val ROADMAP = listOf(
    LevelRoadmapItem(1, 0, "基础皮肤、默认音效"),
    LevelRoadmapItem(2, 100, "解锁 Dark 深色主题"),
    LevelRoadmapItem(3, 300, "键盘徽章样式 ×1"),
    LevelRoadmapItem(4, 700, "自定义短语槽位 +5"),
    LevelRoadmapItem(5, 1400, "解锁基础音效包"),
    LevelRoadmapItem(6, 2500, "周输入统计报告"),
    LevelRoadmapItem(7, 4200, "解锁 Material 主题、字体大小档位"),
    LevelRoadmapItem(8, 6800, "剪贴板收藏槽位扩展"),
    LevelRoadmapItem(9, 10500, "动态皮肤 ×1"),
    LevelRoadmapItem(10, 16000, "自定义键盘背景图")
)

// ===== 页面骨架 =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LevelScreen(state: LevelState, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的等级") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回", fontSize = 14.sp) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LevelCard(state)
            StatsRow(state)
            RoadmapSection(state)
            PrivacyNote()
        }
    }
}

// ===== 等级卡片 =====

@Composable
private fun LevelCard(state: LevelState) {
    val progress = LevelEngine.progressInLevel(state.totalPoints)
    val curThreshold = LevelEngine.thresholdForLevel(state.level)
    val nextThreshold = LevelEngine.nextLevelThreshold(state.level)
    val isMax = state.level >= LevelEngine.MAX_LEVEL

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 等级徽章
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Lv.${state.level}",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = LevelEngine.levelName(state.level),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Spacer(modifier = Modifier.height(16.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isMax) {
                    "已达最高等级，累计 ${state.totalPoints} 分"
                } else {
                    "距升级还差 ${nextThreshold - state.totalPoints} 分（${state.totalPoints}/$nextThreshold）"
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
        }
    }
}

// ===== 统计行 =====

@Composable
private fun StatsRow(state: LevelState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatItem(
            modifier = Modifier.weight(1f),
            value = "${state.todayChars}",
            label = "今日上屏字数"
        )
        StatItem(
            modifier = Modifier.weight(1f),
            value = "+${state.todayPoints}",
            label = "今日积分"
        )
        StatItem(
            modifier = Modifier.weight(1f),
            value = "${state.streakDays}",
            label = "连续天数"
        )
        StatItem(
            modifier = Modifier.weight(1f),
            value = "${state.totalPoints}",
            label = "累计积分"
        )
    }
}

@Composable
private fun StatItem(modifier: Modifier, value: String, label: String) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ===== 等级路线图 =====

@Composable
private fun RoadmapSection(state: LevelState) {
    Text(
        text = "等级路线图",
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            ROADMAP.forEach { item ->
                RoadmapRow(item = item, currentLevel = state.level)
                if (item.level < LevelEngine.MAX_LEVEL) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun RoadmapRow(item: LevelRoadmapItem, currentLevel: Int) {
    val reached = currentLevel >= item.level
    val isCurrent = currentLevel == item.level

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isCurrent) Modifier.background(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                ) else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 等级圆形标识
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    if (reached) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${item.level}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (reached) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = LevelEngine.levelName(item.level),
                fontSize = 14.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (reached) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Text(
                text = item.benefit,
                fontSize = 12.sp,
                color = if (reached) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }

        // 状态标识
        Text(
            text = when {
                isCurrent -> "当前"
                reached -> "✓"
                else -> "${item.threshold}分"
            },
            fontSize = 12.sp,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            color = when {
                isCurrent -> MaterialTheme.colorScheme.primary
                reached -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            }
        )
    }
}

// ===== 隐私说明 =====

@Composable
private fun PrivacyNote() {
    Text(
        text = "隐私说明：所有统计均为本地脱敏聚合计数（字数、天数），" +
                "绝不记录任何输入内容，数据仅存储在本机。",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}
