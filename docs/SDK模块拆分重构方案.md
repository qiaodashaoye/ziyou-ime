# ziyou-ime SDK 模块拆分重构方案

> 目标：将 `ziyou-ime` 拆分为「底层 SDK 模块（rime-sdk）」与「上层应用/业务模块（app）」两个独立 Gradle 模块。
> SDK 封装与 librime 的全部交互及输入法通用基础能力，可打包为 AAR 供其他应用集成；
> 上层模块只负责 UI 展示、用户交互与业务逻辑（等级、扩展词库、技能、皮肤、更新、语音、AI 增强等）。
>
> 现状基线见 [ARCHITECTURE.md](../ARCHITECTURE.md)；本文档为重构蓝图，落地后回写 ARCHITECTURE.md。
>
> **后续演进（已落地）**：P5 之后 `:rime-sdk` 进一步独立为隔壁工程 `ziyou-rime-sdk`
> （自主打包 AAR，坐标 `com.ziyou:rime-sdk`），ziyou-ime 经坐标依赖 +
> `includeBuild` composite 消费；第三方集成文档见 `ziyou-rime-sdk/INTEGRATION.md`。

---

## 1. 现状梳理与耦合点分析

当前 Gradle 结构：`:app`（全部引擎/JNI/UI/业务）→ `:core-logic`（纯逻辑）。

SDK 化视角下的关键耦合点：

| 耦合点 | 现状 | 拆分影响 |
|--------|------|----------|
| JNI 源码 | `app/src/main/jni/librime_jni/`，CMake 经 `../../../../../` 定位根目录 `libs/<abi>/librime.a` | 随 SDK 模块迁移；目录层级不变则 CMake 相对路径无需改动（见 §6.1） |
| JNI 符号名 | C++ 侧 `Java_com_ziyou_ime_core_RimeNative_*` 硬编码 Kotlin 包名 | 迁移期**保持包名不变**，避免 21 个导出符号同步改名（见 §8 风险 R1） |
| 部署步骤装配 | `di/AppContainer` 把 `AssetDeployer` / `PredictDbManager` / `DictManager.regenerateMainDict` 注入 `RimeSession.deploySteps` | 该依赖反转设计已就绪：`RimeDeployStep` 接口下沉 SDK，具体步骤留在 app 装配 |
| 上屏横切 | `commitListeners`（等级计分）/ `commitTextObservers`（LLM 续写）经组合根注入 `InputLogicController` | 注入机制下沉 SDK，监听实现留在 app |
| `InputLogicController` | 通用按键事务（Mutex 串行、`processKeyBulk`、上屏路由）与业务（T9 状态机协作、图片上屏、剪贴板）混在一个类 | 需拆为 SDK 通用输入管线 + app 业务扩展（见 §3.3） |
| 资源部署 | `AssetDeployer` 内含业务知识：`LEGACY_BUILTIN_DICTS` 清理清单、`luna_pinyin.userdb → rime_frost.userdb` 迁移 | 通用复制/版本对比下沉 SDK；业务清理项改为宿主注入钩子（R3 不变量必须保留） |
| 部署版本触发 | 以宿主 `versionCode` 对比决定是否重部署 | SDK 不得读宿主 versionCode，改为宿主显式传入 `deployVersion`（见 §4.5） |
| ProGuard | `app/proguard-rules.pro` 的 `-keep com.ziyou.ime.core.**` 保 JNI 反射符号 | 改为 SDK 的 `consumer-rules.pro`，随 AAR 下发（见 §6.3） |

---

## 2. 目标架构总览

```
┌──────────────────────────── :app（上层应用/业务模块）──────────────────────────┐
│ UI 层        SettingsActivity / LevelActivity / DictManagerActivity ...        │
│ IME 层       ZiYouInputMethodService · 键盘视图 · 候选/编码视图 · 各面板协调器   │
│ 业务域       level/ · dict/ · skill/ · skin/ · update/ · voice/ · ai/ · data/  │
│ 装配根       di/AppContainer（deploySteps、commitListeners 注入点）             │
└──────────────────────────────┬────────────────────────────────────────────────┘
                               │ api(project(":rime-sdk"))  单向依赖
                               ▼
┌──────────────────────────── :rime-sdk（底层 SDK 模块，交付 AAR）───────────────┐
│ 门面层       RimeSdk（初始化/关闭）· InputSession（通用输入管线）                │
│ 服务层       CandidatesService（候选查询/翻页/选删）                              │
│             PreeditController（编码区状态 StateFlow）                            │
│             SchemaService / OptionService / RimeConfigManager                   │
│ 引擎层       RimeEngine / RimeSession / RimeDeployStep · RimeApi / SimpleRimeImpl│
│             RimeDispatcher（单线程）· RimeMessage · Proto 数据类                 │
│ 部署层       AssetDeployer（通用 assets→files 部署 + userdb 迁移框架）           │
│ 通用逻辑     KeyCode 映射 · T9PinYinUtils · KeyRecordStack · prediction 纯逻辑   │
├──────────────────────────────────────────────────────────────────────────────┤
│ JNI 层       rime_jni.cc / config.cc / session.h / objconv.h（src/main/jni）    │
│ Engine       libs/<abi>/librime.a + libs/include（librime-prebuilt 产出）       │
└──────────────────────────────────────────────────────────────────────────────┘
```

**依赖方向**：`:app → :rime-sdk`（编译器强制单向）。`:core-logic` 模块撤销，内容按 §3.4 分流进两个模块。

**命名空间**：SDK 模块 Gradle namespace `com.ziyou.ime.sdk`（新增代码用新包）；
迁移的既有类**保持原包名**（`com.ziyou.ime.core` / `daemon` / `config` 等），零 JNI 符号与调用点改动（见 §8 R1）。

---

## 3. 模块划分建议

### 3.1 `:rime-sdk` 迁入清单

| 现位置（:app） | 类 | 迁移理由 |
|----------------|----|----------|
| `core/` | `RimeNative` `RimeApi` `SimpleRimeImpl` `RimeDispatcher` `ProtoTypes` `RimeConfig` `RimeMessage` | librime 交互核心，SDK 本体 |
| `daemon/` | `RimeEngine` `RimeSession` `RimeDeployStep` | 引擎生命周期，与业务无关 |
| `config/` | `RimeConfigManager`；`AssetDeployer`（改造后） | 引擎配置读取；资源部署通用框架 |
| `ime/` | `KeyCode`（按键码映射）；`InputLogicController` 的通用部分（拆出为 `InputSession`，见 §3.3） | 与业务无关的输入处理 |
| `src/main/jni/librime_jni/` | 全部 JNI/CMake | native 层随 SDK 走 |

### 3.2 `:app` 保留清单

- 全部 UI：`ui/`、`ime/` 下的视图与协调器（`*KeyboardView`、`SimpleCandidatesView`、`PreeditOverlayView`、`PinyinSideBarView`、各 `*PanelCoordinator`、`KeyboardLayoutManager`、`DisplayModeController`、`ZiYouInputMethodService`）。
- 业务域：`level/`、`dict/`、`skill/`、`skin/`、`update/`、`voice/`、`ai/`、`data/`。
- 配置持久化：`SchemaPreference`、`DisplayModeManager`（含 DEFAULT_SCHEMA_ID 契约，留在 app）。
- 装配根 `di/AppContainer`：继续负责把业务部署步骤与上屏监听注入 SDK。

### 3.3 `InputLogicController` 拆分方案（核心难点）

按「是否与具体业务/UI 形态相关」一刀切：

**下沉 SDK → `InputSession`**（`com.ziyou.ime.sdk.input`）：
- `inputMutex` 事务串行化；`processKey` / `processKeyBulk` 调用编排；
- 慢按键告警、`MAX_INPUT_LENGTH` 编码上限守卫；
- 上屏路由骨架：引擎消费的 commit → `CommitSink`；未消费键的退格/可打印字符回退；
- 九宫格消歧原语：`selectPinyin`（`replaceKey` + `XK_End`）、`restorePinyin`、`clearComposition`、`commitComposition`；
- `KeyRecordStack` 协作（状态机本体随 :core-logic 的 t9 包一起下沉）。

**留在 app → `ZiYouInputController`**（包装 `InputSession` 的薄层）：
- T9/技能面板的 `CommitTarget` 改道路由；
- 图片上屏（`ImageCommitHelper`）、剪贴板写入、侧栏符号上屏；
- `commitListeners` / `commitTextObservers` 的具体实现注入。

### 3.4 `:core-logic` 内容分流

| 内容 | 去向 | 理由 |
|------|------|------|
| `util/T9PinYinUtils`、`core/t9/KeyRecordStack` | **:rime-sdk** | T9 是输入法通用能力，且 `InputSession` 直接依赖 |
| `core/prediction/*`（LruCache/StreamCandidateText/AdoptionRecord） | **:rime-sdk** | 候选缓存/流式解析与引擎候选语义绑定，属通用增强 |
| `core/level/LevelEngine` | **:app**（`level/` 包内） | 纯业务计分规则 |
| `core/skill/*`、`core/markdown`、`core/floating/FloatingPanelGeometry`、`util/AppVersionUtils` | **:app** | 技能/渲染/悬浮形态/应用更新均为业务 |

分流后 `:core-logic` 模块删除；两模块各自保留对应 `src/test` 单测，
`scripts/unit-test-baseline.txt` 基线重新统计（用例总数不得下降）。

---

## 4. SDK 公共 API 设计

设计原则：
1. **门面收口**：外部集成方只见 `RimeSdk` + 少量 Service 接口；`RimeNative` / `SimpleRimeImpl` / `RimeDispatcher` 等标 `internal` 或 `@RestrictTo(LIBRARY)`。
2. **接口不变量前移**：候选与编码区以不可变快照 + `StateFlow` 交付，UI 层零解析 Proto 细节。
3. **宿主适配反转**：SDK 不 import 任何 app 类；上屏、生命周期能力经 `ImeHost` 接口由宿主注入。

### 4.1 门面 `RimeSdk`

```kotlin
object RimeSdk {
    /** 初始化：幂等，内部串行化（承接 RimeSession.lifecycleMutex 语义） */
    suspend fun init(context: Context, config: RimeSdkConfig)

    /** 引擎操作总入口（即现 RimeApi，经引擎接口暴露） */
    val engine: RimeEngine          // initialize/redeploy/destroy/api/messageFlow/initialized

    val input: InputSession          // 通用输入管线（§4.2）
    val candidates: CandidatesService// 候选（§4.3）
    val preedit: PreeditController   // 编码区（§4.4）
    val schemas: SchemaService       // 方案列表/当前方案/切换/选项读写
    val configManager: RimeConfigManager

    suspend fun shutdown()
}

data class RimeSdkConfig(
    val assetRoot: String = "rime",              // assets 下引擎资源目录
    val deployVersion: Long,                     // 宿主显式传入（替代读宿主 versionCode）
    val fullCheckVersion: Long = deployVersion,  // 需要 fullCheck 重部署的版本号
    val preDeploySteps: List<RimeDeployStep> = emptyList(),  // 宿主业务步骤（词库注入等）
    val postDeploySteps: List<RimeDeployStep> = emptyList(),
    val userDbMigrationRules: List<UserDbMigrationRule> = emptyList(), // 如 luna→frost
    val legacyCleanupFiles: List<String> = emptyList(),      // 宿主旧内置词库清理清单
    val logTag: String = "RimeSdk",
)
```

装配关系：SDK 内部把「通用 assets 部署 + userDbMigrationRules + legacyCleanupFiles」
拼成首个 `RimeDeployStep`，再接宿主 `preDeploySteps`（predict.db 回盖、`regenerateMainDict`），
最后启动引擎——与现 `AppContainer.defaultEngine` 的三步装配语义一一对应。

### 4.2 输入管线 `InputSession` + 宿主适配 `ImeHost`

```kotlin
class InputSession internal constructor(...) {
    /** 处理一个按键；返回是否被引擎消费。内部 Mutex 串行 + processKeyBulk 单次跨界 */
    suspend fun processKey(keycode: Int, mask: Int): Boolean

    suspend fun selectCandidate(index: Int, global: Boolean = false): ContextProto?
    suspend fun changePage(backward: Boolean): ContextProto?
    suspend fun selectPinyin(command: ReplaceCommand): ContextProto?   // 九宫格锁定音节
    suspend fun restorePinyin(command: ReplaceCommand): ContextProto?  // 智能退格还原
    suspend fun clearComposition(); suspend fun commitComposition()

    /** 上屏目的地抽象：SDK 不感知 InputConnection，宿主实现 */
    var commitSink: CommitSink?
}

interface CommitSink {
    fun commitText(text: String): Boolean
    fun deleteSurroundingText(length: Int): Boolean
    fun sendKeyDownUp(keyCode: Int): Boolean    // 未消费功能键透传编辑器
}
```

上屏事件另经 `SharedFlow<CommitEvent(text, codePoints)>` 广播，
app 侧把等级计分（脱敏码点数）与 LLM 续写（文本）两类观察者挂上，
完整保留现有 `commitListeners` / `commitTextObservers` 双通道语义隔离。

### 4.3 候选词查询与管理 `CandidatesService`

```kotlin
class CandidatesService internal constructor(...) {
    /** 当前上下文候选快照：随每次 context 变更由 SDK 内部刷新 */
    val snapshot: StateFlow<CandidatesSnapshot>

    suspend fun query(startIndex: Int, limit: Int): List<CandidateProto>  // 懒加载深翻页
    suspend fun select(index: Int, global: Boolean = false): Boolean
    suspend fun delete(index: Int, global: Boolean = false): Boolean
    suspend fun changePage(backward: Boolean): Boolean
}

data class CandidatesSnapshot(
    val items: List<CandidateProto>,   // 当前页
    val total: Int,
    val highlightedIndex: Int,
    val pageIndex: Int,
    val isPrediction: Boolean,          // 联想态（菜单非空且编码为空）→ UI 强调色整栏
)
```

要点：`isPrediction` 判定（现 `InputLogicController.updateUI` 内联逻辑）收敛到 SDK，
T9 preview comment 归一化契约（候选 comment 规范化而非破坏）继续在 SDK 侧统一执行。

### 4.4 编码区状态管理 `PreeditController`

```kotlin
class PreeditController internal constructor(...) {
    /** 编码区唯一事实源；由 context.preedit 派生，主线程可直接 collect 渲染 */
    val state: StateFlow<PreeditState>

    suspend fun clear()
    suspend fun commit()                                   // 提交当前编码
    suspend fun replaceSegment(caretPos: Int, length: Int, replacement: String)  // 代理 replaceKey
}

data class PreeditState(
    val rawText: String,           // 空串 ⇒ UI 置 GONE（承接 PreeditOverlayView 空值规范）
    val caretPos: Int,
    val selStart: Int, val selEnd: Int,
    val segments: List<Segment>,   // 音节切分，供高亮/点击定位
) { val isEmpty: Boolean get() = rawText.isEmpty() }
```

要点：
- 视图（`PreeditOverlayView`）留在 app，只消费 `state`；空值规范（null/空串 GONE）由 app 视图保持；
- 九宫格拼音预览（`PinyinHintProvider`）依赖 `ContextProto` + `T9PinYinUtils`，属通用逻辑，
  一并下沉 SDK 作为 `PreeditController.t9Preview(context): T9Preview` 的可选能力，app 视图订阅。

### 4.5 方案/选项/消息

```kotlin
class SchemaService internal constructor(...) {
    suspend fun list(): List<SchemaItem>
    suspend fun current(): String
    suspend fun select(schemaId: String): Boolean
    suspend fun setOption(key: String, value: Boolean)
    suspend fun getOption(key: String): Boolean
    suspend fun syncUserData(): Boolean
}
// 消息流沿用 RimeEngine.messageFlow（SchemaMessage/OptionMessage/DeployMessage/UnknownMessage）
```

---

## 5. 数据流转方案

### 5.1 新分层数据流（按键到上屏，对应原数据流 A）

```
[app] QwertyKeyboardView.onTouchEvent
   → ZiYouInputMethodService.handleSoftKeyPress
   → ZiYouInputController.processKey                    # app 薄层：业务路由前置判断
   → [SDK] InputSession.processKey（inputMutex 串行）
       → RimeApi.processKeyBulk → RimeDispatcher → RimeNative(JNI) → librime
       ← KeyEventResult(consumed, commit, context)
   → [SDK] 内部副作用：
       ├ preedit.state ← PreeditState(context.preedit)
       ├ candidates.snapshot ← CandidatesSnapshot(context.menu)
       └ commit?.text → CommitSink.commitText（宿主注入）+ CommitEvent 广播
   → [app] collect state/snapshot（StateFlow 天然主线程安全）→ 刷新编码区/候选栏
   → [app] CommitEvent 观察者：LevelStats.onCommit（脱敏）/ LLM 续写（开关门控）
```

与原实现的差异仅两点：① UI 刷新由「Service 回调 renderContext」改为「collect SDK 状态流」，
消除 app→SDK→app 的回调环；② Mutex 与批量跨界逻辑整体内聚到 SDK，app 不再触碰 `RimeNative`。

### 5.2 九宫格消歧（对应原数据流 B）

链路不变，落点变化：`KeyRecordStack` 状态机实例由 app Service 持有（UI 生命周期绑定），
`ReplaceCommand` 的执行（`selectPinyin`/`restorePinyin`）调 SDK `InputSession`；
`T9PinYinUtils` 还原拼音提示在 SDK `PreeditController.t9Preview` 完成，
`PinyinSideBarView`（app）订阅结果。

### 5.3 扩展词库安装到生效（对应原数据流 C）

```
[app] DictManagerViewModel.installDict → DictManager（下载/校验 sha256/写安装记录）
   → DictManager.regenerateMainDict
   → RimeSdk.engine.redeploy(context)        # SDK 执行重部署
   ← messageFlow DeployMessage → app UI 提示「词库已生效」
```

`regenerateMainDict` 属业务（词库注入规则）留 app；SDK 只暴露 `redeploy` 与
`RimeDeployStep` 扩展点——现有依赖反转结构原样保留。

### 5.4 线程模型（不变）

```
UI/Main 线程 ──suspend──▶ SDK InputSession/Services ──dispatch──▶ Rime 线程（单线程，JNI）
      ▲                        │ JNI 回调 → SharedFlow.tryEmit（线程安全）
      └── StateFlow collect ◀──┘
部署/词库 IO：Dispatchers.IO（SDK 内 redeploy 与 app 业务 IO 各自独立，互不阻塞热路径）
```

---

## 6. AAR 打包与交付

### 6.1 CMake/JNI 迁移

- `app/src/main/jni/librime_jni/` 整体移入 `rime-sdk/src/main/jni/librime_jni/`；
  `externalNativeBuild { cmake { path = ... } }` 随 `android {}` 块移入 `rime-sdk/build.gradle.kts`。
- CMake 中 `get_filename_component(... "${CMAKE_SOURCE_DIR}/../../../../../" ABSOLUTE)`
  定位仓库根 `libs/`：`rime-sdk` 与 `app` 目录深度相同，**该相对路径无需修改**。
- `librime.a` 仍由 `librime-prebuilt/build.sh` 产出到根 `libs/<abi>/`，位置不变；
  每个发布 ABI 必须先产出对应 `librime.a` 的既有规则不变。
- 模块开关（`-DWITH_PREDICT/LUA/WITOGRAM=ON`）随 `externalNativeBuild.cmake.arguments`
  移入 SDK 模块，且必须继续与预编译库侧开关一致（不一致即 undefined symbol 链接失败）。

### 6.2 AAR 产物形态

- `:rime-sdk` 为 `com.android.library`，AAR 内含：Kotlin 类（classes.jar）、
  `jni/<abi>/librime_jni.so`（librime.a 已静态链入 .so，消费方无需再碰静态库）、
  `consumer-rules.pro`、空或最小 assets（引擎方案/词库 assets 属内容，归宿主，见 §6.4）。
- **JAR 形态说明**：纯 JAR 无法携带 `.so`，仅适用于「消费方自行集成 native 库」的
  特殊场景，不作为默认交付；默认且推荐形态为 AAR。
- 发布：`maven-publish` 插件，坐标建议 `com.ziyou:rime-sdk:<version>`，
  独立版本号与宿主 `versionCode` 解耦（由此引出 §4.5 的 `deployVersion` 显式传参）。

### 6.3 Consumer ProGuard 规则

新建 `rime-sdk/consumer-rules.pro`，自 `app/proguard-rules.pro` 迁入 JNI 保活规则：

```proguard
# JNI 反射查找的 Proto/回调类（objconv.h 缓存 jclass）
-keep class com.ziyou.ime.core.** { *; }
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
```

app 侧对应规则删除，避免双份维护。

### 6.4 assets 归属决策

引擎方案/词库（`assets/rime/**`）是**产品内容**而非 SDK 能力：留在 `:app`。
SDK 的 `AssetDeployer` 只负责「把宿主指定 assetRoot 递归复制到 filesDir + 版本对比 +
userdb 迁移钩子」，不感知具体文件。收益：其他集成方可自带词库资产；
字由自身部署行为不变（含 `migrateUserDb` 守卫不变量，经 `userDbMigrationRules` 声明）。

---

## 7. 分阶段迁移步骤

| 阶段 | 内容 | 验证标准 |
|------|------|----------|
| **P0 基线保护** | 跑通 `./gradlew testDebugUnitTest`，确认 `scripts/unit-test-baseline.txt` 基线；git 建分支 `feat/sdk-split` | 基线绿 |
| **P1 物理迁移** | 建 `:rime-sdk` 模块；按 §3.1 迁移 `core/` `daemon/` `RimeConfigManager` `AssetDeployer` `KeyCode` + JNI 目录；**包名保持不变**；`:app` 改 `implementation(project(":rime-sdk"))`（此阶段用 `api` 暴露以最小化调用点改动） | assembleDebug 通过；JNI 21 符号可加载；部署/输入冒烟 |
| **P2 输入管线拆分** | 从 `InputLogicController` 抽出 `InputSession` + `CommitSink`（§3.3/§4.2）；app 留 `ZiYouInputController` 薄层 | 数据流 A/B 真机冒烟；单测迁移不降基线 |
| **P3 状态服务化** | 落地 `PreeditController` / `CandidatesService` / `SchemaService`（§4.3~4.5）；`PreeditOverlayView` / `SimpleCandidatesView` 改订阅 StateFlow；`:core-logic` 按 §3.4 分流并删除该模块 | T9 消歧、联想态渲染、翻页选词回归通过；`AssetDeployer.migrateUserDb` 守卫语义专项用例通过 |
| **P4 门面与收口** | 落地 `RimeSdk` / `RimeSdkConfig`；`AppContainer` 改为组装 `RimeSdkConfig` 并 `RimeSdk.init`；内部类标 `internal`/`@RestrictTo`；consumer-rules 迁位 | `overrideRimeEngine` 式测试替身仍可用；release 构建 R8 通过 |
| **P5 AAR 发布管线** | `maven-publish` 配置；`publishToMavenLocal` 产出 AAR；另建最小 demo module（或 `samples/`）仅依赖 AAR 跑通「初始化 → 按键 → 候选渲染」 | AAR 独立集成冒烟通过；回写 ARCHITECTURE.md 与 README |

每阶段独立可合入（保持主干绿），P1~P4 预计各 0.5~1.5 天，P5 约 0.5 天。

---

## 8. 风险与必须保留的不变量

| # | 风险/不变量 | 处置 |
|---|-------------|------|
| R1 | JNI 符号名与 Kotlin 包名绑定（`Java_com_ziyou_ime_core_RimeNative_*`） | 迁移期不改包名；如未来统一改名须同批改 21 个 C++ 导出 + `RimeNative` 声明，列为独立任务 |
| R2 | librime 非线程安全：单线程 Dispatcher + 输入事务 Mutex | 二者整体内聚 SDK，语义原样保留，禁止在 SDK 外直接调 `RimeNative` |
| R3 | `AssetDeployer.migrateUserDb` 守卫必须为「目标目录**已有内容**才跳过」（引擎会预创建空 `rime_frost.userdb`，按存在性跳过将永久阻断迁移） | 迁移框架下沉时该守卫写成显式用例（目标存在但为空 → 仍执行迁移） |
| R4 | `LEGACY_BUILTIN_DICTS` 清理项与复用文件同名撞车历史（`cn_dicts/others.dict.yaml` 教训） | 清理清单作为 `legacyCleanupFiles` 由 app 传入，SDK 默认不清理任何文件 |
| R5 | `DEFAULT_SCHEMA_ID` 与 `default.yaml schema_list` 首项对齐契约 | 二者均属内容层，留在 app，SDK 不感知默认方案 |
| R6 | 部署触发依赖宿主 versionCode，SDK 独立版本后语义漂移 | `RimeSdkConfig.deployVersion` 显式传参（§4.1/4.5），app 继续传自身 versionCode，行为不变 |
| R7 | T9 候选 comment 归一化契约、`max_homophones` 与用户词升权等引擎侧约束 | 随 `PinyinHintProvider`/候选服务下沉时原样保留，既有守卫测试随之迁移 |
| R8 | R8 `-keep com.ziyou.ime.core.**` 双份维护漂移 | P4 起只保留 SDK consumer-rules 一份 |
| R9 | 单测基线（发布脚本比对用例数）跨模块统计变化 | P3 删除 :core-logic 后重新统计并更新 `unit-test-baseline.txt`，总数不得下降 |

---

## 9. 内容资源与语法模型归属决策（词库 / 方案 / witogram）

本节对「词库与方案资源的引入逻辑」和「witogram 语法模型的管理与加载」给出明确归属。
核心判据是**四层切分**：先区分「能力」与「内容」，再区分「框架」与「编排」。

```
┌──────────────────────────────────────────────────────────────────────┐
│ ① 引擎能力层     librime.a（witogram/lua/predict 已静态编入）+ JNI     │ → :rime-sdk
│                  模块注册声明（rime_require_module_*）+ CMake WITH_* 开关│
├──────────────────────────────────────────────────────────────────────┤
│ ② 部署框架层     AssetDeployer 通用复制/版本对比/userdb 迁移框架        │ → :rime-sdk
├──────────────────────────────────────────────────────────────────────┤
│ ③ 内容资源层     assets/rime/**：schema/dict/lua/opencc/grammar/*.gram │ → :app（宿主）
│                  predict.db —— 全部是产品内容，SDK 不携带              │
├──────────────────────────────────────────────────────────────────────┤
│ ④ 内容编排层     DictManager（下载/启停/regenerateMainDict）、           │ → :app（宿主）
│                  SchemaPreference（DEFAULT_SCHEMA_ID）、grammar 段调参  │
└──────────────────────────────────────────────────────────────────────┘
```

### 9.1 决策结论

| 事项 | 归属 | 一句话理由 |
|------|------|-----------|
| witogram 插件二进制（已链入 `librime.a`）与 JNI 模块注册 | **:rime-sdk** | 编译期能力，随 native 产物天然内聚，宿主无法也不应单独注入 |
| `rime_jni.cc` 的 `declare_librime_module_dependencies()` 与 CMake `-DWITH_WITOGRAM=ON` | **:rime-sdk** | 与预编译库的 `WITH_*` 开关构成配对契约（不一致即 undefined symbol），只能由持有 .so 的模块维护 |
| 部署框架（assets→files 复制、`deployVersion` 对比、userdb 迁移钩子） | **:rime-sdk** | 通用机制，与具体文件内容无关 |
| `assets/rime/**`（schema/dict/lua/opencc）与 `assets/rime/grammar/zh-moqi.gram` | **:app** | 产品内容：字由选什么词库、哪个默认方案、用什么语法模型，是业务决策 |
| `rime_frost.schema.yaml` 的 grammar 段（`language: zh-moqi`、`non_collocation_penalty: -4`） | **:app** | 是 schema 内容的一部分；调参属于产品体验决策（上游 -12 对白霜惩罚过重才改 -4） |
| 扩展词库下载/启停/`regenerateMainDict`、predict.db 回盖 | **:app**（经 `preDeploySteps` 注入 SDK） | 业务编排；依赖反转通道已在 §4.1 保留 |

### 9.2 逐条回应考量因素

**1. 资源管理：SDK 是否负责 assets 的部署与迁移？**
SDK 负责**部署机制**，不负责**资源所有权**。`AssetDeployer` 下沉后是一个通用管道：
「把宿主声明的 `assetRoot` 复制到 filesDir + 按 `deployVersion` 判定是否重部署 +
执行 `userDbMigrationRules`」。宿主自带内容、自定清单（`legacyCleanupFiles`）、
自决迁移规则。这样既复用了版本对比/迁移守卫（含 R3 的「目标有内容才跳过」不变量），
又不把字由的词库选择焊死在 SDK 里。

**2. 模型集成：witogram 由 SDK 统一管理还是应用层按需注入？**
**分两半**：
- *插件本体*（编译产物）只能由 SDK 统一管理。witogram 经 superbuild 静态链入
  `librime.a`，再由 SDK 的 CMake 链成 `librime_jni.so`——AAR 交付后宿主拿到的就是
  已含 witogram 的二进制，不存在「应用层注入 .a/.so」的通道；JNI 侧的
  `rime_require_module_witogram()` 注册声明也必须在持有该 .so 的模块里（静态库链接
  下不注册即无符号）。把开关交给宿主反而制造「开关与二进制不匹配 → 链接失败」的新风险。
- *模型数据与启用配置*（`grammar/zh-moqi.gram` 7MB + schema grammar 段）由应用层持有。
  librime poet 侧有判空保护（模型缺失时 grammar 段仅产生日志、静默降级，不影响基础输入），
  因此「能力常在、内容可选」是天然安全的：宿主不放 .gram 即得到无语法惩罚的普通输入。

**3. 配置灵活性：词库/方案放进 SDK 会不会限制定制？**
会，且无任何收益。若 schema/dict 入 SDK：① 每个集成方被迫接受字由的词库与默认方案
（`DEFAULT_SCHEMA_ID` 契约被 SDK 固化）；② 词库内容更新（白霜迁移、扩展词库通道）
被迫与 SDK 版本耦合发布；③ SDK 体积膨胀（essay.txt 3.8MB + 词库数 MB）。放在宿主侧则：
其他 App 可自带仓颉/五笔方案集，字由自身的扩展词库下载管线（DictManager）不受影响。
SDK 通过**能力探测**保障兼容而非捆绑内容：建议在 `RimeApi` 增补
`suspend fun getAvailableModules(): Set<String>`（读 librime 已注册模块），
宿主部署前可校验「我的 schema 依赖 witogram/lua，当前 SDK 二进制是否编入」。

**4. 构建与分发：AAR 形态下如何平衡通用性与特异性？**
- AAR 内只有能力（`librime_jni.so`，librime.a 已静态链入）+ 部署框架 + consumer-rules，
  **零内容 assets**；内容与 SDK 版本独立演进（词库热更新走既有扩展词库通道，不必发版）。
- witogram 静态编入会抬升所有集成方的 .so 体积——若未来出现轻量需求，演进方案是
  **SDK AAR 变体**（`rime-sdk-full` 全模块 / `rime-sdk-core` 仅基础），由
  `librime-prebuilt/build.sh` 的 `WITH_*` 组合产出多份 `librime.a` 支撑；
  当前单变体先行，变体矩阵列入 §10 后续演进。
- 契约面：SDK 文档需声明「二进制能力清单（predict/lua/witogram）」与
  「内容目录约定（`grammar/<language>.gram`、schema `grammar.language` 命名对齐）」，
  宿主照约定放内容即插即用。

### 9.3 对 §3 迁入清单的修正

- `assets/rime/grammar/` 与 §6.4 结论一致：**留 `:app`**，SDK assets 为空；
- `RimeApi` 增补 `getAvailableModules()` 能力探测接口（P4 门面阶段落地）；
- `rime_frost.schema.yaml` grammar 段注释中「随 WITH_WITOGRAM 编入」的依赖关系改述为：
  「SDK 二进制编入 witogram（能力）× 宿主 assets 提供 zh-moqi.gram（内容），二者缺一即静默降级」。

---

## 10. 后续演进（本次不做）

- SDK 包名统一化（`com.ziyou.ime.*` → `com.ziyou.rime.sdk.*`）与 JNI 符号同步改名；
- 多会话支持（`RimeSession` 由 `object` 演进为可实例化，共享 librime 单例引擎）；
- 词库/方案内容的独立分发单元（assets 内容包与 SDK 版本解耦，配合扩展词库通道）；
- 若引入 Hilt/Koin，SDK 保持零 DI 框架依赖，仅暴露工厂函数。
