# AI 模块重构方案：基础问答 + 人设润色与知识库增强（RAG）

> 分支：`feat/ai-persona-polish`
> 状态：已实施（含后续调整：工具栏双入口 + 知识库与人设强绑定 + 人设管理页）
> 关联现状代码：`ai/AiConfig.kt`、`ai/AiPersona.kt`、`ai/PersonaRepository.kt`、
> `ai/AiChatClient.kt`、`ai/knowledge/KnowledgeRepository.kt`、`ai/knowledge/KnowledgeSearcher.kt`、
> `core-logic/core/rag/*`、`ime/AiPanelView.kt`、`ime/AiPanelCoordinator.kt`、
> `ui/SettingsActivity.kt`、`ui/KnowledgeActivity.kt`

---

## 1. 需求定位

### 1.1 两大核心功能

1. **基础 AI 问答**：用户在设置中配置 API Key 即可使用（现状 `AiConfig` + `AiChatClient` + `AiPanelView` 已完整，保持不动）。
2. **人设模块（本期核心，全新交互）**：人设不再是「问答时的回答口吻」，而是**独立的润色/改写功能**：
   - 用户在键盘上输入的文本**不直接上屏**；
   - 输入内容经 AI 按当前人设润色/改写，输出符合人设风格的句子（2~3 个候选）；
   - 用户对候选**二选一处置**：发送上屏，或要求重新润色（可附调整要求）。
   - 人设可自定义（名称/简介/系统提示词，现状模型已支持），并可**绑定专属知识库**（如「李白」绑定其诗词与生平语料），润色时检索注入以贴合角色的语言风格与知识背景。

### 1.2 现状能力与差距

| 组件 | 现状 | 本次处理 |
| --- | --- | --- |
| `AiConfig` | SP 存 API URL/Key/Model，预置 8 家平台，`isConfigured()` | 复用，+持久化上次面板模式 |
| `AiChatClient` | OpenAI 兼容非流式请求，安全基线齐备 | 完全复用 |
| `AiPersona`/`PersonaRepository` | 人设模型 + 内置 5 个 + 自定义 CRUD | **扩展**：+知识库绑定字段 |
| `KnowledgeRepository`/`Importer` | 条目元数据 + chunk 落盘 + SAF 导入 | 复用 |
| `KnowledgeSearcher` + `Bm25Index`（core-logic） | 全库 BM25 检索 | **扩展**：按条目子集过滤 |
| `RagPromptBuilder`（core-logic） | 问答式 prompt 融合 | **扩展**：润色任务变体 |
| `AiPanelView` | 问答面板：标题栏 + 气泡区 + 输入行 + 输入路由接管 | **改造**：双模式（问答/润色） |
| `AiMemoryStore` | 会话持久化 + 跨会话摘要 | 摘要按人设分槽；**润色内容不落盘**（§8） |

关键差距：
1. 无「润色」模式——面板只有问答一条管线；
2. 人设与知识库无关联，无法做角色专属检索；
3. 无候选产出与「上屏/重新润色」处置交互；
4. `AiPanelView.sendQuestion()` 已揉 80 行业务，再加润色分叉必须先抽编排层。

**一个有利条件**：面板打开期间键盘上屏已被 `aiCommitTarget` 改道注入面板输入框
（[AiPanelCoordinator.open](file:///Users/qpg/StudioProjects/kb/ziyou-ime/app/src/main/java/com/ziyou/ime/ime/AiPanelCoordinator.kt#L64-L74)）——「输入不直接上屏」是面板既有行为，**润色模式无需新增任何输入路由机制**，输入框即「草稿区」。

---

## 2. 总体架构

依赖方向保持 `:app` → `:core-logic` 单向。不新增 Gradle 模块。

```
┌────────────────────────── :app ──────────────────────────┐
│  UI 层     SettingsActivity（人设编辑 + 知识库绑定多选）    │
│            KnowledgeActivity（条目展示「已绑定 N 个角色」）  │
│  IME 层    AiPanelView                                     │
│            ├─ 模式段控 [问答 | 润色] + 人设 chip            │
│            ├─ 问答模式：现状气泡流（不动）                   │
│            └─ 润色模式：草稿输入 → 候选卡片 → 上屏/重润      │
│  编排层    AiChatOrchestrator【新增】双模式单一业务入口      │
│            ask()   → 问答管线（现状逻辑迁入）               │
│            polish()→ 检索 → 润色 prompt → 请求 → 解析候选   │
│  数据层    PersonaRepository（+knowledgeItemIds）          │
│            KnowledgeRepository（不变）                     │
│            AiMemoryStore（摘要按 personaId 分槽）          │
└──────────────────────────────┬───────────────────────────┘
                               ▼
┌────────────────────── :core-logic ───────────────────────┐
│  core/rag  Bm25Index（+docFilter 检索重载）                │
│            Retriever / KnowledgeSearcher 子集检索          │
│            RagPromptBuilder（+buildPolish 润色变体）       │
│  core/ai   PolishResultParser【新增】候选编号解析（纯逻辑）  │
└──────────────────────────────────────────────────────────┘
```

**新增 `AiChatOrchestrator` 的理由**：双模式下「检索源决策、prompt 融合、请求、结果解析」分叉倍增，面板层只保留 UI（气泡/候选卡片/输入路由），编排逻辑纯 suspend、无 View 依赖、可 JVM 单测。符合项目「UI 不持有业务决策」纪律。

**新增 `PolishResultParser` 于 `:core-logic`** 的理由：候选解析是确定性纯文本逻辑，按项目纪律下沉到 core-logic 并配套单测（与 `T9PinYinUtils`、`KeyRecordStack` 同一纪律）。

---

## 3. 数据模型演进

### 3.1 `AiPersona` 增加知识库绑定

```kotlin
data class AiPersona(
    val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val isBuiltin: Boolean = false,
    /** 绑定的知识条目 ID；空 = 无专属知识。仅自定义人设可绑定。 */
    val knowledgeItemIds: List<String> = emptyList()
)
```

- **单向关联存于人设侧**：检索是人设驱动的（润色时从当前人设出发），读路径零成本；删人设自动解绑。
- **反向清理**：`KnowledgeRepository.removeItem` 后调 `PersonaRepository.purgeKnowledgeRefs(itemId)`（人设与条目均为个位数规模，遍历无压力）。
- **持久化兼容**：`PersonaRepository` JSON 增可选字段 `knowledgeItemIds`（`optJSONArray` 兜底空列表），旧数据零迁移。

### 3.2 检索源规则（知识库与人设强绑定）

无全局知识库开关，检索范围完全由当前人设的绑定关系驱动：

| 条件 | 检索行为 |
| --- | --- |
| 人设 `knowledgeItemIds` 非空 | 仅绑定条目内检索（问答与润色两面板同规则） |
| 人设未绑定 | 不检索（纯人设问答/润色） |

绑定关系的唯一建立入口是「人设管理」页创建/编辑人设时的勾选，
而非独立上传/开关流程；知识条目的导入仍由 `KnowledgeActivity` 承担。

---

## 4. 交互流程设计

### 4.1 工具栏双入口：两个独立面板

「AI 问答」与「人设润色」是候选区功能栏上的两个独立按钮（`ToolbarItem.AI` /
`AI_POLISH`，功能码 -110/-118，各自图标），分别经 `AiPanelCoordinator.openAsk()` /
`openPolish()` 打开对应面板；模式构造时固定，面板内无切换控件，两入口可直接互切。
默认工具栏含四核心按钮（技能/粘贴板/AI/润色）。润色面板形态如下：

```
┌────────────────────────────────────────────────┐
│ [🎭李白 📚3▾]   人设润色    新对话  ✕   │  ← 人设 chip + 面板标题
├────────────────────────────────────────────────┤
│  润色模式·结果区：                               │
│  ┌ 原句 ──────────────────────────────┐        │
│  │ 今天天气不错，想出去走走。            │        │
│  └──────────────────────────────────┘         │
│  候选 1  举杯邀月，天地为友，今日…    [上屏]     │
│  候选 2  清风入怀，正宜仗剑出游…      [上屏]     │
│  候选 3  …                            [上屏]     │
│              [🔄 重新润色已并入输入行按钮]                      │
├────────────────────────────────────────────────┤
│ [输入调整要求（可选）：更豪放一些…]  [重新润色] │
└────────────────────────────────────────────────┘
```

要点：
- **两个工具栏按钮即模式开关**，无需面板内切换；存量已自定义工具栏的用户可在设置页功能栏定制或应用预设模板添加「润色」键。
- **人设 chip** 点击展开人设浮层（内置风格 / 我的角色，📚N 徽标，长按看提示词预览，底部「＋新建角色」跳人设管理页）——两个面板共享当前人设。
- **两面板会话独立**：问答历史与润色会话各自内存结构，互不干扰。

### 4.2 润色主流程（核心诉求落地）

```
① 工具栏点「润色」键打开润色面板 → chip 选人设（默认上次）
② 用户在键盘打字
     面板打开期间 commitTarget 已改道 → 文本进入面板草稿框，天然不上屏 ✔
③ 点「润色」/ 回车
     键盘自动收回（复用 onRequestKeyboardCollapsed(true) 答案态编排）
     Orchestrator.polish(draft, persona, feedback=null, history)
④ 候选返回 → 原句卡 + 2~3 候选卡（每卡附「上屏」按钮）+「重新润色」
⑤ 用户处置（二选一）：
   a. 点候选卡「上屏」→ host.onCommitAnswer(text) 直达宿主编辑器
      → 面板保留、清候选、恢复键盘（连续润色下一句）
   b. 点「重新润色」→ 可先在草稿框输入调整要求（占位符提示）
      → 带调整要求 + 上轮候选历史重新请求（收敛而非随机重试）
⑥ 「新对话」清空润色会话重新开始
```

**输入框双态**（润色模式下）：
- 无候选结果时：占位符「输入要润色的文字…」，按钮「润色」；
- 有候选结果后：占位符「输入调整要求（可选）：更简洁/更正式…」，按钮「重新润色」——
  空内容点击即纯重生成，非空则作为调整指令。无需额外 UI，一框两用。

### 4.3 人设管理页（PersonaManagerActivity）

独立的纯代码 View 页面，统一查看/切换/编辑/删除人设及其绑定关系：

- 列表三行展示：名称 + 徽标（当前✓ / 内置 / 📚绑定数）→ 简介 →
  已绑定知识条目名（未绑定时明示「纯人设，无专属检索」）；
- 单击切换当前人设；长按弹操作菜单（预览/编辑/删除）；预览含绑定清单；
- 创建/编辑对话框：名称/简介/提示词三输入 + **知识库绑定多选**（绑定
  是知识库使用的唯一入口，强绑定）；删除人设同步清其摘要槽；
- 入口：设置页「AI 人设」项（复用现有入口，改为跳转页面）与键盘人设
  浮层「＋新建角色」；`KnowledgeActivity` 退居纯条目导入/管理职责。

---

## 5. RAG 技术实现方案（润色场景）

### 5.1 端到端管线

```
用户草稿 draft（回车/润色按钮）
   │
   ▼ AiChatOrchestrator.polish(draft, persona, feedback, polishHistory)
   │
   ├─ 1. 检索决策：persona.knowledgeItemIds 非空 → scopedIds = 绑定集；否则不检索
   │
   ├─ 2. KnowledgeSearcher.ensureLoaded()（IO，懒构建；未开知识库零开销纪律不变）
   │
   ├─ 3. KnowledgeSearcher.retrieve(query, topK, scopedIds)
   │      query = draft（检索「与草稿内容相关」的语料：题材/意象/事件）
   │      → BigramTokenizer.tokenize → Bm25Index.search(tokens, topK, docFilter)
   │      打分阶段过滤（见 5.2），异常/空命中降级为无参考资料润色
   │
   ├─ 4. RagPromptBuilder.buildPolish(base, personaPrompt, chunks)
   │      → 人设 + 润色任务指令 + 【风格参考资料】
   │
   ├─ 5. AiChatClient.ask(context, userContent, systemPrompt, polishHistory)
   │      userContent = feedback 为空时即 draft；否则「原文：draft\n调整要求：feedback」
   │
   └─ 6. PolishResultParser.parse(answer) → List<PolishVariant(text, note)>
          清洗（SensitiveWordFilter）→ 候选卡片渲染
```

### 5.2 按知识子集过滤检索

**采纳：BM25 打分阶段过滤**。`Bm25Index.search` 增加重载：

```kotlin
/** [docFilter] 非空时仅集合内文档参与打分排序（循环内 O(1) 判断）。 */
fun search(queryTokens: List<String>, topK: Int, docFilter: Set<Int>? = null): List<ScoredDoc>
```

- 过滤发生在打分阶段而非 Top-K 之后 → 召回质量等价于子库独立索引，且索引全库只建一份（多角色可共享语料）；
- `KnowledgeSearcher` snapshot 构建时顺带生成 `itemId → docIds` 反向表，`Set<String> itemIds` 到 docId 集 O(1) 级转换；
- 否决方案：取 topK×4 后过滤——绑定库小时尾部召回劣化，不采纳。

润色场景 `topK` 由 4 降为 **3**：参考资料仅作风格参照，过多 chunk 挤占润色输出 token 且诱导模型照抄资料。

### 5.3 润色 Prompt 组装（`RagPromptBuilder.buildPolish`）

```
{BASE_SYSTEM_PROMPT}                 ← 格式约束（候选输出不用 Markdown，见下）

{persona.systemPrompt}               ← 角色身份、语气、用词风格

【风格参考资料】（chunks 非空才出现，≤预算截断）
[1]（来源：李白诗全集.txt）
……chunk 原文……

【润色任务】
你正在执行「文本润色」：请以你的角色口吻改写用户提供的原文。
1. 保留原文的核心意思与关键信息，不得增删事实；
2. 语气、用词、句式须符合你的角色设定；有参考资料时仅借鉴其
   风格与意象，不得大段抄袭或与原文无关地引用；
3. 输出 2~3 个改写版本，格式严格为：
   1. <版本一文本>（风格说明，不超过15字）
   2. <版本二文本>（风格说明）
   每个版本一行，版本内不要换行，不要使用 Markdown；
4. 除编号版本外不要输出任何其他内容。
```

- `MAX_PROMPT_CHARS=6000` 预算纪律复用；
- 问答路径 `build()` 签名与行为完全不变（零回归）；
- 「保留原意、不增删事实」是润色安全底线，防 LLM 幻觉篡改用户表达。

### 5.4 候选解析（`:core-logic` 新增 `core/ai/PolishResultParser`）

```kotlin
/** 润色候选：改写文本 + 可选风格说明。 */
data class PolishVariant(val text: String, val note: String = "")

object PolishResultParser {
    /**
     * 解析「N. 文本（说明）」编号行；容忍全角句点/顿号编号、
     * 说明缺失、首尾杂散行（丢弃）。无任何有效编号行时整体作为
     * 单候选兜底（模型不守格式也不让用户空手）。
     */
    fun parse(raw: String): List<PolishVariant>
}
```

单测覆盖：标准多候选、全角编号、无说明、杂散前后缀、零编号兜底、空输入。

---

## 6. 多轮一致性与迭代润色

### 6.1 迭代润色历史（`polishHistory`）

- 面板内存维护 `polishHistory: MutableList<ChatMessage>`，上限 6 条 FIFO：
  - user：`原文：…\n调整要求：…`（首轮仅原文）；
  - assistant：上一轮候选原文；
- 重新润色时随请求携带 → 模型知晓已产出过哪些版本，**避免重复、朝用户偏好收敛**；
- 候选人选「上屏」后该轮历史保留一轮（用户可能想基于上屏版本再润色下一句），点「新对话」清空。

### 6.2 角色一致性

- system prompt 每轮重建且恒含人设 + 润色指令 → 风格不漂移；
- **切换人设即清空润色会话与候选**（沿用 `switchToPersona` 清会话纪律，风格/知识域不可混用）；
- 跨会话摘要按 personaId 分槽（`AiMemoryStore` 改造同前案）：**仅问答模式触发摘要**，润色内容不参与（§8 隐私）。

---

## 7. 核心代码改动清单与实现思路

### 7.1 `:core-logic`（纯逻辑，全部配套单测）

| 文件 | 改动 |
| --- | --- |
| `core/rag/Bm25Index.kt` | `search` 增 `docFilter` 重载 + 过滤/等价性用例 |
| `core/rag/Retriever.kt` | 接口增 `retrieve(query, topK, itemIds: Set<String>?)` 默认方法 |
| `core/rag/RagPromptBuilder.kt` | 增 `buildPolish(base, personaPrompt, chunks)` + 单测（指令存在、预算不破、问答路径零变化） |
| `core/ai/PolishResultParser.kt`【新增】 | 候选解析 + `PolishResultParserTest`（§5.4 用例矩阵） |

### 7.2 `:app`

| 文件 | 改动 |
| --- | --- |
| `ai/AiPersona.kt` | +`knowledgeItemIds`（默认空） |
| `ai/PersonaRepository.kt` | JSON 增字段；+`purgeKnowledgeRefs(itemId)` |
| `ai/AiConfig.kt` | 不变（模式由入口按钮决定，不持久化） |
| `ai/knowledge/KnowledgeSearcher.kt` | snapshot 增 `itemId→docIds` 表；子集检索；润色 topK 常量 |
| `ai/knowledge/KnowledgeRepository.kt` | `removeItem` 后调用引用清理 |
| `ai/knowledge/AiMemoryStore.kt` | 摘要按 personaId 分槽 + 旧数据迁移至 `builtin_assistant`；删人设清槽 |
| `ai/AiChatOrchestrator.kt`【新增】 | `ask()`（问答面板，人设绑定驱动检索）+ `polish()`（见下骨架） |
| `ime/AiPanelView.kt` | 构造参数固定模式 + 人设 chip/浮层 + 润色候选卡片 + 输入框双态 + `polishHistory` |
| `ime/AiPanelCoordinator.kt` | `openAsk()/openPolish()` 双入口互切 + 人设管理页跳转回调 |
| `ime/KeyCode.kt`/`ime/ToolbarItem.kt`/`ime/ToolbarIconDrawer.kt`/`data/ToolbarConfig.kt` | 新增「润色」工具栏按钮（功能码 -118 / 人物+星光图标 / 默认四核心预设） |
| `ui/PersonaManagerActivity.kt`【新增】 | 人设管理页：列表（绑定关系三行展示）/切换/预览/编辑（绑定多选）/删除 |
| `ui/SettingsActivity.kt` | 「AI 人设」入口改跳人设管理页，原管理对话框代码迁出 |
| `ui/KnowledgeActivity.kt` | 移除全局启用开关；条目行「已绑定 N 个角色」徽标 + 删除确认文案 |

### 7.3 `AiChatOrchestrator.polish` 骨架

```kotlin
object AiChatOrchestrator {

    /** 润色结果：候选列表（已清洗）+ 本次实际引用的来源名（渲染「参考」行用）。 */
    data class PolishOutcome(
        val variants: List<PolishVariant>,
        val sources: List<String>,
        val rawError: String? = null   // 非空表示失败，面板渲染错误气泡
    )

    suspend fun polish(
        context: Context,
        persona: AiPersona,
        draft: String,
        feedback: String?,
        history: List<ChatMessage>
    ): PolishOutcome {
        val appContext = context.applicationContext
        val scopedIds = persona.knowledgeItemIds.takeIf { it.isNotEmpty() }?.toSet()
        var chunks: List<RetrievedChunk> = emptyList()
        val systemPrompt = withContext(Dispatchers.IO) {
            try {
                if (scopedIds != null) {
                    KnowledgeSearcher.ensureLoaded(appContext)
                    chunks = KnowledgeSearcher.retrieve(
                        draft, POLISH_TOP_K, scopedIds)
                }
                RagPromptBuilder.buildPolish(
                    AiChatClient.BASE_SYSTEM_PROMPT, persona.systemPrompt, chunks)
            } catch (e: Exception) {
                chunks = emptyList()   // 检索失败降级：纯人设润色
                RagPromptBuilder.buildPolish(
                    AiChatClient.BASE_SYSTEM_PROMPT, persona.systemPrompt, emptyList())
            }
        }
        val userContent = if (feedback.isNullOrBlank()) draft
            else "原文：$draft\n调整要求：$feedback"
        AiUsageStats.recordQuestion(appContext, chunks.size)
        return AiChatClient.ask(appContext, userContent, systemPrompt, history).fold(
            onSuccess = { answer ->
                AiUsageStats.recordSuccess(appContext)
                val variants = PolishResultParser.parse(answer)
                    .map { it.copy(text = answerFilter.sanitize(it.text)) }
                PolishOutcome(variants, chunks.map { it.sourceName }.distinct())
            },
            onFailure = { e ->
                AiUsageStats.recordFailure(appContext)
                PolishOutcome(emptyList(), emptyList(), e.message ?: "润色失败，请稍后重试")
            }
        )
    }
}
```

面板侧对应缩减为：组 `userContent` 历史 → `polish()` → 渲染原句卡/候选卡/错误卡，
候选卡「上屏」调 `host.onCommitAnswer(variant.text)` 后 `onRequestKeyboardCollapsed(false)` 恢复键盘（区别于问答答案态的常驻收起）。

### 7.4 兼容与回归

- 全部扩展为默认参数/新增方法：问答管线（含全局 RAG）、`AiChatClient`、导入器行为零变化；
- `AiPersona` 新字段带默认值，内置人设与旧自定义数据零迁移；
- 两面板模式由入口按钮决定，首次打开体验与现状一致。

---

## 8. 隐私与纪律对齐（项目红线）

1. **润色内容不落盘**：草稿与候选仅存面板内存，`release()` 即销毁；不进
   `AiMemoryStore` 会话持久化、不触发跨会话摘要——用户原创文本的隐私面最小化。
   问答模式现状持久化行为保持不变。
2. 润色请求仅发往**用户自配**的 AI 端点（HTTPS 强制、现有安全基线），无新增上报；
   知识库与绑定关系仅存本机。
3. 润色在面板协程 IO 路径执行，不触碰输入热路径（面板打开期间输入已被路由改道，
   与 librime/RimeDispatcher 无任何交集）。
4. 回答/候选渲染前经 `SensitiveWordFilter` 清洗（与现状一致）。

---

## 9. 实施计划与测试

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| P0 | 数据模型：`AiPersona.knowledgeItemIds` + 仓库读写 + `purgeKnowledgeRefs` + `AiConfig` 模式持久化 | 序列化往返、引用清理单测 |
| P1 | 检索过滤：`Bm25Index.docFilter` + `KnowledgeSearcher` 子集检索 | 子集只返回绑定条目；空集/未加载安全返回空 |
| P2 | `RagPromptBuilder.buildPolish` + `PolishResultParser` + `AiChatOrchestrator.polish` + 记忆分槽 | prompt 断言；解析用例矩阵（§5.4）；编排降级路径单测 |
| P3 | UI：工具栏双入口 + 人设 chip/浮层 + 候选卡片 + 输入框双态 + 人设管理页 + 知识库页徽标 | 手工验证清单 |

### 手工验证清单（P3）

1. 「李白」绑定诗词语料 → 润色「今天天气不错想出去走走」→ 候选带盛唐风格且不偏离原意；「参考」行显示绑定语料名；
2. 候选「上屏」→ 文本进入宿主编辑器，键盘恢复，可立即输入下一句；
3. 重润 + 调整要求「更豪放一些」→ 新候选明显区别上轮（历史生效）；
4. 模式切回「问答」→ 现状问答与全局 📚 行为逐项回归无差异；
5. 未配置 API Key → 两模式均出现设置引导卡；
6. 删除已绑定知识条目 → 角色徽标计数自动减少，润色降级不崩；
7. 重开面板 → 回到上次模式与上次人设。

### 单测基线

新增用例计入 `scripts/unit-test-baseline.txt`（只增不减）；已有 `Bm25IndexTest`/`RagPromptBuilderTest` 扩用例，`PolishResultParserTest` 新建。

---

## 10. 风险与备选

| 风险 | 缓解 |
| --- | --- |
| 模型不遵守候选编号格式 | 解析器单候选兜底（§5.4）；prompt 中「严格格式」约束 + 负例禁止 |
| 白话草稿与文言语料词汇重合低、BM25 召回差 | 检索 query 自动追加人设名（如「李白」）作扩词；润色 topK 已收紧为 3，弱命中时宁可少注 |
| 润色改写过度（偏离原意） | prompt「保留核心意思、不增删事实」+ 候选附风格说明让用户可辨识 |
| 候选 2~3 个拉长响应时间 | 非流式单次请求即得全部候选（优于逐个生成）；超时沿用 60s 读超时 |
| 向量检索天花板 | P2+ 备选：评估 sherpa-onnx（已在依赖内）本地 embedding 混合检索；本期不引入 |
