# 字由输入法 架构设计文档

本文档描述字由输入法的系统架构、设计决策与各模块实现细节，面向希望深入理解或参与贡献的开发者。
概览与使用说明见 [README.md](README.md)；各功能的设计推演过程见 `docs/` 下的可行性方案文档。

## 架构概览

字由输入法按 **本仓 `:app` 单模块 + 隔壁独立 SDK 工程** 组织（SDK 拆分蓝图与迁移记录见
[docs/SDK模块拆分重构方案.md](docs/SDK模块拆分重构方案.md)）：`:app`（Android 应用：UI + 业务域）
以本地文件 AAR 消费隔壁工程 `ziyou-ime-sdk` 的产物（`app/libs/ime-sdk-release.aar` 随仓入库，
`implementation(files(...))` 引入，零外部仓库依赖；升级时在 ziyou-ime-sdk 执行 `assembleRelease` 后覆盖），依赖方向**单向、由编译器强制**。
五层引擎栈（UI → IME → Core → JNI → Engine）中 Core/JNI/Engine 三层与通用输入管线位于 SDK 工程；
`:app` 内按 UI 层 / IME 层 / 业务域组织：

```
┌────────────────────────────── :app 模块 ──────────────────────────────┐
│                              UI 层                                       │
│  SettingsActivity(传统 View 主设置)                                     │
│  LevelActivity / DictManagerActivity / SkillManagerActivity /           │
│  SkinActivity / KnowledgeActivity / PersonaManagerActivity /            │
│  ImeSetupActivity / SkillDevGuideActivity / SkinDevGuideActivity …      │
│                              (Jetpack Compose Material3)                │
├────────────────────────────────────────────────────────────────────────┤
│                              IME 层                                       │
│  ZiYouInputMethodService（生命周期 + 视图装配 + 事件分发，职责持续外移）  │
│   ├ InputLogicController（业务薄层：CommitTarget 路由/上屏监听/图片上屏） │
│   ├ KeyboardLayoutManager（键盘视图装载 / 复合布局组装）                  │
│   ├ EngineSyncController（布局切换 → 引擎方案/模式同步 → UI 刷新）        │
│   ├ DisplayModeController（停靠/悬浮形态解析、切换与悬浮 insets）         │
│   ├ PinyinHintProvider（九宫格拼音提示/预览，纯逻辑）                     │
│   └ 7 个面板协调器（技能/AI/涂鸦/粘贴板/工具/键盘选择/语音，BasePanelHost）│
│  键盘视图: BaseKeyboardView → Qwerty / NineGrid / Symbol / Number        │
│  候选/编码: SimpleCandidatesView · CandidateToolbarView ·                │
│             PreeditOverlayView · PinyinSideBarView                       │
│  面板容器: SkillPanelContainer(WebView) · FloatingPanelContainer(悬浮)    │
├────────────────────────────────────────────────────────────────────────┤
│                          业务域 + DI（:app）                              │
│  di/AppContainer(组合根)  ·  level/ 等级  ·  dict/ 扩展词库 + predict.db │
│  skill/ 技能插件  ·  skin/ 皮肤  ·  voice/ 语音  ·  update/ 应用更新      │
│  ai/ AI 问答+人设+知识库 RAG  ·  ai/prediction LLM 智能续写               │
│  core/ 业务纯逻辑(level/skill/skin/voice/rag/floating/clipboard/…)      │
│  data/ 侧栏符号/剪贴板历史/工具栏配置/联想开关                            │
└────────────────────────────────────────────────────────────────────────┘
                                 │ 依赖（单向，本地文件 AAR 消费）
                                 ▼
┌─────────────────────── ziyou-ime-sdk（com.ziyou:ime-sdk）─────────────┐
│  Core 层   RimeApi · SimpleRimeImpl · RimeDispatcher · RimeNative        │
│            RimeEngine(接口) ← RimeSession(internal 实现) · RimeSdk 门面  │
│            core/t9(KeyRecordStack 状态机) · core/prediction(纯逻辑)      │
│  输入管线  sdk/input: InputSession(Mutex 事务) + CommitSink/InputHostAdapter│
│            sdk/state: PreeditController / CandidatesService / SchemaService│
│  JNI 层    src/main/jni/librime_jni/（rime_jni.cc · config.cc · RAII）   │
│  Engine 层 librime（预编译 librime.a，含 predict / witogram 插件）        │
└────────────────────────────────────────────────────────────────────────┘
```

**依赖方向**：`:app` → `ziyou-ime-sdk`（单向，编译器强制）。SDK 内部不感知宿主业务：
部署步骤经 `RimeSdk.init(RimeSdkConfig.preDeploySteps)` 由组合根反转注入，
上屏目的地经 `CommitSink` 由宿主注入，持久化与业务编排全部留在 `:app`。

## 设计原则

### 1. 单线程 Dispatcher
librime 不是线程安全的，所有 Rime API 调用必须在同一线程执行。`RimeDispatcher` 封装一个
`SingleThreadExecutor`，借助 `withContext` 将所有 Rime 操作自动调度到专属线程，调用方只用 `suspend` 函数。

> **输入事务串行化**：`RimeDispatcher` 仅保证**单次** `dispatch` 原子，不保证「一次按键 =
> `processKey`→`getCommit`→`getContext`」内的多次调用连续执行。SDK 的 `InputSession` 用一个
> `Mutex`（`inputMutex`）把每个输入操作整体串行化，Kotlin `Mutex` 公平排队天然保持按键先后顺序。

### 2. RAII 资源管理
JNI 层使用 C++ RAII 避免资源泄漏：`SessionHolder`（会话）、`CString`（UTF 字符）、`JRef`（LocalRef）、`JString`（JNI 字符串）。

### 3. 批量 API 调用
减少 JNI 跨界次数。`getRimeBulkCandidates()` 一次返回候选词列表、总数与高亮索引；
`processRimeKeyBulk()` 把按键热路径的 `processKey + getCommit + getContext` 合并为单次跨界，
一次按键仅 1 次主线程↔Rime 线程往返与 1 次 JNI 调用。

### 4. 模块条件编译
通过 CMake `option()` 控制可选模块（Lua、Octagram、Predict、Witogram、OpenCC）的编译链接，
未启用模块不引入依赖，保持最小二进制体积。当前预编译库已启用 Predict 与 Witogram。

### 5. 视图职责分离
编码区（preedit）、候选词列表、功能按钮栏、拼音侧栏拆分为独立 View
（`PreeditOverlayView` / `SimpleCandidatesView` / `CandidateToolbarView` / `PinyinSideBarView`），
通过垂直/横向/叠放布局组合，各自内聚、互不干扰。键盘视图统一由 `BaseKeyboardView`
抽象绘制/触摸/主题，新增布局只需继承子类。

### 6. 热路径零磁盘 IO
输入热路径（`onCommit`）仅做 O(1) 内存操作：等级计分原子自增、LLM 采纳攒批 O(1) 计数，
达阈值或生命周期节点才后台异步落盘。LLM 续写关闭时热路径仅一次 SharedPreferences 内存布尔读。

### 7. 引擎接口化 + 依赖注入
引擎能力抽象为 `RimeEngine`（生命周期）与 `RimeApi`（操作）两个接口，生产实现分别为
`RimeSession`（`internal object`）与 `SimpleRimeImpl`（internal），外部统一经 SDK 门面 `RimeSdk` 使用。
调用方经 `di/AppContainer`（组合根）获取协作对象，依赖接口而非全局单例；
测试可用 `overrideRimeEngine()` / `overrideSpeechEngine()` 注入替身（详见「DI 容器」一节）。

### 8. Service 薄层化
`ZiYouInputMethodService` 只保留「Android 生命周期 + 视图容器装配 + 事件分发」，
输入逻辑、引擎同步、形态管理、键盘装载、各面板编排全部外移到协作类，
每个协作类经 `Host`/`Callbacks` 接口反向获取 Service 能力，保持依赖单向（详见「IME 层」一节）。

## 应用初始化与引擎预热（ZiyouApplication）

`ZiyouApplication.onCreate` 只做两件进程级的事（其余初始化延迟到引擎启动流程中异步执行）：

```
ZiyouApplication.onCreate()
  ├─ prewarmRimeEngineIfNeeded()              # 后台预热 Rime 引擎（fire-and-forget）
  │    ├─ 触发条件（满足其一）:
  │    │    ① AssetDeployer.needsDeploy：首次安装/版本升级，资源复制 + 词库编译的重活
  │    │       必须提前到进程启动，否则全部落在键盘首次弹出的时刻
  │    │    ② 本输入法已被用户启用（InputMethodManager.enabledInputMethodList）：
  │    │       键盘随时可能拉起，引擎保持热态
  │    │  其余进程启动（如仅跑更新检测）不加载引擎，避免无谓内存与电量开销
  │    └─ appScope.launch { AppContainer.rimeEngine.initialize(fullCheck=needsDeploy) }
  │       预热失败不影响正确性：IME 服务 onCreate 会再次触发初始化兜底
  └─ AppUpdateManager.scheduleAutoCheckIfNeeded()   # 仅主进程：静默更新检测（24h 频控，
                                                    # 结果暂存，待前台 Activity 弹窗）
```

- `appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`：SupervisorJob 隔离一次性任务失败；
- 与 IME 服务/设置页的 `initialize` **幂等兼容**：`RimeSdk` 内部 `lifecycleMutex + isInitialized`
  守卫保证不会双重初始化（引擎预热的完整推演见 [docs/智能预测可行性方案.md](docs/智能预测可行性方案.md) 的启动链路约定）。

## IME 层：Service 与视图层解耦

### ZiYouInputMethodService 的职责收敛

为使 `ZiYouInputMethodService` 聚焦「Android 生命周期 + 视图装配 + 事件分发」，职责按同一拆分纪律
持续外移到协作类。Service 通过**匿名 Host 对象**为每个协作类提供其所需的 Service 能力切片
（引擎访问、容器引用、上屏出口等），协作类之间互不引用，只经 Service 组合：

| 协作类 | 职责 | 反向接口 |
|--------|------|----------|
| `InputLogicController` | 业务薄层：CommitTarget 上屏路由、编辑器 CommitSink、横切监听、图片上屏 | `Callbacks` |
| `KeyboardLayoutManager` | 键盘视图创建与复合布局组装（侧栏 + 网格） | `Callbacks` |
| `EngineSyncController` | 布局切换 → 引擎方案/模式同步 → UI 刷新全链路 | `Host` |
| `DisplayModeController` | 停靠/悬浮形态解析、切换与悬浮窗口 insets | `Host` |
| `PinyinHintProvider` | 九宫格拼音候选与编码区预览（纯逻辑，可独立单测） | — |
| `SkillPanelCoordinator` | 技能面板生命周期与三态布局编排 | `Host` |
| `AiPanelCoordinator` | AI 问答 / 人设润色双面板 | `Host` |
| `DoodlePanelCoordinator` | 涂鸦画板面板（图片发送/保存） | `Host` |
| `ClipboardPanelCoordinator` | 粘贴板历史面板 | `Host` |
| `ToolPanelCoordinator` | 工具面板（Logo 键入口） | `Host` |
| `KeyboardPickerCoordinator` | 键盘选择面板（主键盘布局切换） | `Host` |
| `VoicePanelCoordinator` | 语音输入面板（会话编排） | `Host` |
| `ImageCommitHelper` | 图片发送/保存出口收敛（AI 答案图 + 涂鸦快照） | 构造注入 |

**面板公共宿主 `BasePanelHost`**：7 个面板 Host 共享的 5 个方法
（`contentLayout` / `keyboardContainer` / `candidatesContainer` / `keyboardView` / `onPanelWillOpen`）
统一由 Service 的 `basePanelHost` 实现一次，各面板 Host 委托到此避免重复。
面板互斥：`handleSoftKeyPress` 中任何面板键先 `closeAllPanels()` 再开目标面板；
`closeAllPanels` 幂等，供互斥切换、视图重建、`onTrimMemory` 内存回收与 Service 销毁统一调用。

### 输入视图结构

```
onCreateInputView() → buildInputView(displayModeCtrl.refresh())
  内容根（垂直 LinearLayout，皮肤背景图设在根容器）
  ├─ [技能面板叠层（打开时）]
  ├─ 候选容器（垂直 LinearLayout）
  │    └─ 叠放 FrameLayout
  │         ├─ textArea: PreeditOverlayView(顶) + SimpleCandidatesView(下)
  │         └─ CandidateToolbarView（无候选时显示功能按钮栏，有候选时隐藏）
  └─ 键盘容器（FrameLayout）→ KeyboardLayoutManager.install(当前布局)
  FLOATING 形态：整体包裹进 FloatingPanelContainer（拖拽/位置持久化/停靠按钮）
```

不使用 `onCreateCandidatesView()`——该 API 的系统级显隐控制不可靠，候选区放在
`onCreateInputView()` 内可确保始终可见。

### 生命周期时序

```
onCreate()
  ├─ LevelStats.init(applicationContext)                    # 等级计分注入
  ├─ 注册 LLM 续写结果回调（llmAppendAllowed 守卫 + CandidateFusion 去重）
  ├─ IO 线程预热符号键盘 YAML 分类缓存（SymbolRepository.preload）
  ├─ serviceScope.launch { 引擎未初始化则 initialize(fullCheck=needsDeploy) }  # 预热兜底
  ├─ SkinManager.addListener(skinChangeListener)            # 换肤重建输入视图
  └─ 监听 rime.messageFlow（方案/选项/部署消息）
onStartInput()
  └─ LLM 协调器 reset()    # 同 App 切换输入框不回调 onStartInputView，
                           # 必须在此清词窗口防跨输入框上下文泄漏（隐私红线）
onStartInputView()
  ├─ displayModeCtrl.refreshIfChanged() → 形态变化则重建输入视图
  ├─ syncEnterKeyLabel()（换行键文案随编辑器动作：搜索/发送/换行）
  ├─ 剪贴板兜底同步 + startClipboardWatch()（键盘可见期才监听，耗电审计 P0）
  ├─ LLM reset() + prewarm()（词窗口预热每服务实例至多一次）
  ├─ engineSync.scheduleEngineSync(10s) { clearComposition() }   # 重部署窗口期等待就绪
  └─ IO 线程 LevelRepository.checkInToday()（每日签到，幂等）
[输入中] 软键/物理键 → handleSoftKeyPress/onKeyDown → InputSession（SDK 事务）→ renderContext
onFinishInputView()
  ├─ stopClipboardWatch() + closeAllPanels()
  ├─ resetInputState + 清空编码区 + LevelStats.flush()（主动落盘计分）
  ├─ LLM flush()（候选缓存 + 采纳攒批落盘）+ reset()（会话结束清上下文）
  └─ clearComposition()
onTrimMemory(level)
  ├─ ≥ RUNNING_LOW：closeAllPanels() + RimeNative.trimNativeHeap()（归还部署残留 native 页）
  └─ ≥ RUNNING_CRITICAL：连语音识别模型一并 release（经 AppContainer 懒获取可重建）
onDestroy()
  ├─ LevelStats.flush() + stopClipboardWatch() + SkinManager.removeListener
  ├─ serviceScope.cancel()
  └─ 释放视图引用
```

### EngineSyncController：引擎状态同步机制

`ime/EngineSyncController.kt` 承接「布局切换 → 引擎方案/模式同步 → UI 刷新」全链路，
是从 Service 剥离的关键协作类。所有方法须在主线程调用。

**核心 API 与机制：**

```kotlin
class EngineSyncController(private val host: Host) {
    companion object {
        ENGINE_READY_POLL_MS = 50L          // 等待引擎就绪的轮询间隔
        ENGINE_READY_TIMEOUT_MS = 10_000L   // 视图同步类操作的等待超时（词库重部署可能较慢）
        KEY_ENGINE_READY_TIMEOUT_MS = 3_000L // 按键处理的短超时（避免按键响应长时间挂起）
    }
    fun switchKeyboard(type: KeyboardType)          // 布局切换入口：重建视图 + 触发同步（幂等短路）
    fun switchToQwertyEnglish()                     // 九宫格「中→英」专用入口（强制 ascii_mode=true）
    fun scheduleEngineSync(timeoutMs, beforeSync)   // latest-wins 引擎同步调度
    suspend fun awaitEngineReady(timeoutMs): Boolean // 引擎就绪轮询
    var pendingEnglishMode / qwertyEnglishOrigin    // 中→英竞态标志与返回布局记忆
}
```

1. **latest-wins 串行化**：`engineSyncJob` 单 Job，新同步请求到来时取消上一次。所有入口
   （键盘切换 / 获焦 / 部署完成 / 形态与主题切换）共用 `scheduleEngineSync`，消除快速切换
   （如九宫格→全键盘）时滞后同步把引擎切回 t9 的并发交错。
2. **重部署窗口期保护**：词库下载/启用后 `RimeSdk.redeploy` 会销毁并重建引擎，窗口期内
   `rime.api` 抛 `IllegalStateException`。非热路径引擎访问先经 `awaitEngineReady` 轮询等待
   （50ms 间隔），超时放弃本次，由部署完成消息触发的重同步兜底。
3. **「布局 ↔ 方案」映射**（元数据在 `KeyboardType` 枚举，单一事实源）：
   - `NINE_GRID`：强制切 `t9` 专用方案（`forcedSchemaId`）并保证中文模式；
   - `QWERTY`：对齐用户持久化的全键盘方案偏好（`SchemaPreference`，替代早期易失的内存记忆，
     进程重建后不丢）；偏好方案失效时回退默认方案；
   - `SYMBOL` / `NUMBER`：临时面板，只清编码与状态栈，不动方案与 ascii_mode，「返回」后无感恢复；
   - 同步末尾联动 `prediction` 选项（引擎级联想总开关，映射自 `AssociationManager`），
     并把 `ascii_mode` 回写键盘中英态显示，最后 `renderFromEngine()` 刷新全部 UI。
4. **中→英竞态防护**：九宫格「英」键经 `switchToQwertyEnglish` 走同步路径置
   `pendingEnglishMode=true`（由 `applyEngineForKeyboard` 强制英文），绕过
   `handleSoftKeyPress` 的异步 toggle 避免竞态；`qwertyEnglishOrigin` 记录进入前布局，
   QWERTY 英文下按中英键时恢复原布局（任何手动布局切换都会清除该记忆，避免陈旧恢复）。

### DisplayModeController：显示模式管理

`ime/DisplayModeController.kt`（`@MainThread`）管理停靠（DOCKED）/ 悬浮（FLOATING）两种形态，
与键盘布局**正交**（设计推演见 [docs/游戏悬浮输入法可行性方案.md](docs/游戏悬浮输入法可行性方案.md)）：

1. **形态解析优先级**：手动覆盖（本次服务生命周期，`manualModeOverride`） > 悬浮总开关 >
   横屏自动悬浮（`DisplayModeManager` SharedPreferences 持久化）。手动覆盖优先避免
   「手动停靠后下个输入会话又被自动切回悬浮」。
2. **入口分工**：`refresh()`（onCreateInputView 解析并生效）、`refreshIfChanged()`
   （onStartInputView 重解析，形态变化返回新形态由 Service 重建视图）、
   `toggle()`（工具栏「浮」键）、`switchTo()`（内部统一路径）。
3. **切换语义**：`switchTo` 先 `host.beforeModeSwitch()`（丢弃未提交的多击预览等临时状态）→
   持久化开关 → 更新 `currentMode` → `host.onModeSwitched()`（Service 重建输入视图 +
   `engineSync.scheduleEngineSync()` 重同步）。**仅重建视图层，Rime 编码/方案/ascii_mode 不受影响**。
4. **悬浮包裹**：`wrapContent()` 在 FLOATING 时把内容根包进 `FloatingPanelContainer`
   （拖拽/位置持久化/停靠按钮 `onRequestDock` 回调 `switchTo(DOCKED)`），键盘/候选/编码区
   经 `scaleFactor` 统一缩放（`DisplayModeManager.FLOATING_SCALE`）；停靠形态原样返回。
5. **窗口 insets**（`computeInsets`，由 Service 的 `onComputeInsets` 委托，须先执行 super）：
   内容 inset 压到容器底部（宿主应用视键盘高度为 0，游戏画面不被顶起）；触摸区域裁剪为
   悬浮面板矩形、面板外穿透（`TOUCHABLE_INSETS_REGION`）；几何计算委托 `:app` 的
   `FloatingPanelGeometry`（纯逻辑可单测）；面板未布局完成时回退默认 insets，
   避免空触摸区域吞掉所有触摸。拖动位移触发系统重新回调，触摸区域与面板位置实时同步。
6. **全屏禁用**：Service 的 `onEvaluateFullscreenMode() = false`——横屏下系统默认用全屏
   输入框替代应用画面，悬浮形态必须禁用，停靠形态禁用后横屏也能看到原应用界面。

### 键盘视图渲染体系（BaseKeyboardView）

所有键盘布局继承抽象基类 `BaseKeyboardView`（纯 Canvas 绘制，无 XML 布局），共用能力：

- **两种布局模型**：
  - 「行 × 相对宽度」均摊模型（默认）：子类提供 `rows: List<List<Key>>`，键宽按相对权重分配；
  - 「列网格」模型（`gridColumns` 非 null 时启用）：各行共用同一套列宽，`Key.width` 语义变为
    「跨列数」，键数不同的行也能严格对齐（如全键盘第 3 行 Z 对齐第 2 行 S）；
- **Key 数据类**：`label` / `code`（Rime keysym 或自定义功能码）/ `width` / `isFunctional` /
  `letters`（九宫格多字母键承载的字母序列）/ `heightSpan`（纵向跨行）/
  `insetGapStart|End`（列网格模式下的边缘让距）；
- **Canvas 绘制**：背景/圆角/阴影/文字，圆角/间距/阴影/字体均由皮肤参数化；大圆角皮肤作用在
  窄键上时按短边比例收限（`KEY_RADIUS_MAX_RATIO`），避免键面趋近胶囊；
- **触摸**：按下高亮 + 触觉反馈；长按连续重复（400ms 初始延迟 + 60ms 间隔）；
- **皮肤集成**：`applySkin(SkinTheme)` 换肤；`skin` 快照取自 `SkinManager.getCurrentSkin`（O(1) 缓存）；
- **悬浮缩放**：`scaleFactor` 影响键高/间距/圆角/内边距与文字大小，停靠形态 1.0 零影响；
- **状态同步**：`isChineseMode`（Service 层按引擎 `ascii_mode` 回写）、`enterKeyLabel`
  （随宿主编辑器动作变化：搜索/发送/换行，经 SDK 的 `EnterKeyBehavior` 与实际落地语义同源）；
- **统一回调**：`onKeyPress(keyCode, mask)` / `onSwitchKeyboard(target)` /
  `onComposingPreview(preview)`（未提交 Rime 的多击预览实时反馈）/ `onSwitchToQwertyEnglish()`。

子类只需实现 `rows` 与 `handleKeyUp`，可按需覆写 `getKeyDisplayText` / `drawKeyContent` /
`backgroundPaintFor` / `textPaintFor` / `rowIndent` / `gridColumns`：

| 子类 | 布局要点 |
|------|----------|
| `QwertyKeyboardView` | 列网格 4 行，Shift 三态（OFF/ONCE/LOCKED）大小写；Shift 激活态大写字母绕过 Rime 编码直上屏；一键切九宫格 |
| `NineGridKeyboardView` | 4 行网格（3×3 数字键 + 功能列 + 全宽底行），`T9_FAMILY_HEIGHT_FACTOR=1.08`；字母键单击发送对应数字键给 Rime 由 T9 方案消歧，多击预览经 `onComposingPreview` 反馈；悬浮形态 `setFloatingLayout(true)` 底行自带「符」键 |
| `SymbolKeyboardView` | 分类导航 + 符号网格（YAML 分类数据，Service onCreate 后台预热缓存），符号点击经 Service 统一 commit 出口上屏；临时面板 |
| `NumberKeyboardView` | 0-9 + 小数点/正负号等直输，与符号键盘共享侧栏装配逻辑；临时面板 |

**装载与复合布局**（`KeyboardLayoutManager.install`）：停靠形态下九宫格/数字键盘左侧挂载
`PinyinSideBarView`（侧栏 : 网格 ≈ 1.8 : 8.2 权重），布局完成后把网格底行几何同步给侧栏，
使侧栏底部「符号」键与网格底行水平对齐；悬浮形态不挂侧栏。`install` 返回视图引用由 Service 持有。

### 候选 / 编码 / 侧栏视图

- **PreeditOverlayView**：独立编码区，位于候选词列表上方；空值规范——null/空串置 GONE 不占位，有效文本置 VISIBLE；
- **SimpleCandidatesView**：水平滚动候选词横条，点击选词、滑动翻页、高亮当前候选；
  支持预测态强调整栏与 LLM 追加候选（`appendLlmCandidates`，与引擎候选分段索引）；
- **CandidateToolbarView**：与「编码区 + 候选词列表」整体叠放，无候选时显示功能按钮栏
  （Logo 工具面板/语音/AI/涂鸦/剪贴板/悬浮切换等，配置见 `data/ToolbarConfig`），有候选时隐藏；
- **PinyinSideBarView**：九宫格左侧竖排侧栏，两种模式——有候选拼音时展示可点击拼音
  （点击锁定音节），无候选时展示自定义符号 + 「＋」页脚（进入符号管理）。

### 按键码映射 (KeyCode)

```
Android KeyEvent.keyCode → KeyCode.androidKeyCodeToRimeKeyCode() → X11 Keysym
```

- 字母键：ASCII 码值（'a'=0x61）；功能键：X11 keysym（BackSpace=0xff08, Return=0xff0d）；
- 修饰键：`getModifierMask()` 提取 Shift/Ctrl/Alt mask；
- 自定义功能码：`KEYCODE_SWITCH_LANGUAGE`（中英）、`KEYCODE_SWITCH_NUMBER_MODE`（中数转换）、
  `KEYCODE_SWITCH_KEYBOARD` / `KEYCODE_SYMBOL`（符号）、`KEYCODE_TOGGLE_FLOATING`（悬浮切换）、
  `KEYCODE_SKILL_PANEL` / `KEYCODE_AI_ASSISTANT` / `KEYCODE_AI_POLISH` / `KEYCODE_DOODLE_PANEL` /
  `KEYCODE_CLIPBOARD_PANEL` / `KEYCODE_TOOL_PANEL` / `KEYCODE_KEYBOARD_PICKER` /
  `KEYCODE_VOICE_PANEL`（各面板开关）、`KEYCODE_HIDE_KEYBOARD`（收起键盘）等，
  由 `handleSoftKeyPress` 统一路由。

## DI 容器：生产装配与测试注入

`di/AppContainer` 是手写 DI 的**组合根（composition root）**：把「全局单例硬依赖」收敛到
唯一可替换的装配点，后续可平滑迁移到 Hilt/Koin。

**生产装配（默认实现，懒加载）：**

| 属性 | 装配方式 |
|------|----------|
| `rimeEngine` | `by lazy` 首次访问时经 `RimeSdk.init(context, RimeSdkConfig)` 装配：显式传入宿主 `deployVersion`（SDK 版本与宿主解耦）；`preDeploySteps` 注入引擎启动前的部署步骤——SDK 通用资源部署 → `PredictDbManager.reapplyIfInstalled`（predict.db 回盖，须在资源部署后）→ `DictManager.regenerateMainDict`（扩展词库注入）。**SDK 内部不直接 import 业务模块（依赖反转）** |
| `speechEngine` | 懒构造 `SherpaOnnxEngine`；不用 `by lazy`——Service onDestroy 会 release 归还 native 内存而进程可能存活，`isReleased` 单向闩锁要求懒获取处能重建全新实例 |
| `llmPredictionCoordinator` | `by lazy` 构造 LLM 续写协调器，并注入重请求门控 `allowHeavyRequests = PowerNetworkProbe.allowHeavyRequests`（计量网络/低电量未充电时放弃预取预热）。Service 生命周期回调无条件访问本属性，首次进入输入即构造（成本极低） |
| `commitListeners` | 编辑器路径上屏后的**脱敏**监听：`LevelStats.onCommit(codePoints)`（O(1) 内存自增，参数仅码点数） |
| `commitTextObservers` | 编辑器路径上屏后的**文本**观察者：LLM 续写（lambda 内先查开关，关闭时热路径仅一次内存布尔读）。与脱敏监听是两个列表，语义隔离——等级链路永远不见输入内容 |

**测试注入：**

```kotlin
AppContainer.overrideRimeEngine(fakeEngine)     // 替换 Rime 引擎（fake 实现 RimeEngine 接口）
AppContainer.overrideSpeechEngine(fakeSpeech)   // 替换语音识别引擎
// 传 null 恢复默认生产实现
```

涉及引擎的测试（如 `InputLogicControllerTest`）经注入 fake 验证按键/上屏/路由逻辑，
无需真实 librime；`@Volatile` override 字段保证跨线程可见性。

**装配纪律**：SDK 内部不得直接 import 宿主业务模块（部署步骤经 `RimeSdk.init` 反转）；
输入热路径不硬编码业务单例（经 `commitListeners` / `commitTextObservers` 注入）；
面板宿主能力经 `Host` 接口由 Service 注入。

## SDK 输入管线与状态服务

通用输入能力下沉在 ziyou-ime-sdk（门面 `RimeSdk` / `RimeSdkConfig`）：

- **`sdk/input/InputSession`**：通用输入管线。`inputMutex` 把每次按键/选词的多步引擎调用
  串行化为事务；按键热路径经 `processKeyBulk` 单次跨界；承担候选/翻页/T9 消歧
  （`selectPinyin` / `restorePinyin`）与分段确认状态机同步（`deleteUnconfirmedBackward` /
  `retypeUnconfirmed`）；编码上限 `MAX_INPUT_LENGTH`。
- **`CommitSink` / `InputHostAdapter`**：上屏目的地抽象（依赖反转）。宿主（`InputLogicController`）
  实现 `InputHostAdapter`，每次取用按 `commitTarget` 现场解析：面板目标在场时改道注入面板
  输入框（不回调计分/观察者——文本未真正进入编辑器），否则落到宿主编辑器 sink
  （`commitText` → `InputConnection` + 横切通知；`deleteBackward` → `deleteSurroundingText`；
  `onEnter` → `EnterKeyBehavior` 解析编辑器动作，换行时补发 ENTER 物理按键事件）。
- **`sdk/state` 状态服务**：`PreeditController`（编码区快照 StateFlow 事实源）/
  `CandidatesService`（候选快照与选/删/翻页）/ `SchemaService`（方案/选项/用户数据），
  均由 `InputLogicController` 构造时 `newInstance(engine)` 创建并暴露给 UI 层订阅。
- **`core/t9/KeyRecordStack`**：九宫格输入状态机。核心不变式——**列表顺序 == Rime 编码串逻辑顺序**：
  键入数字追加 `T9Key`；选定拼音用 `PinyinKey` **原地替换**首个 T9Key 段；智能退格解锁尾部拼音
  还原为数字段。返回 `ReplaceCommand(caretPos, length, replacement)` 供 `RimeApi.replaceKey`。
  改九宫格逻辑必须维持该不变式并跑通对应单测。
- **`core/prediction` 纯逻辑**：`CommitWordWindow`（上屏词窗口）/ `TriggerPolicy`（触发决策）/
  `ContextLruCache`（扩容 256 + snapshot/restore/recentKeys）/ `StreamCandidateText`（流式增量行解析）/
  `CandidateFusion`（引擎词在前去重融合 + exclude 防复读）/ `AutoPunctPolicy`（预测采纳自动补标点）/
  `AdoptionRecord`（采纳词对攒批）/ `RequestRateWindow`（滚动限流）/ `FailureBackoff`（指数退避）/
  `HeavyRequestGate`（电量/网络门控），全部 JVM 单测覆盖。

## Core / JNI / Engine 层要点

- **RimeDispatcher**：`SingleThreadExecutor` + `asCoroutineDispatcher`，`AtomicBoolean` 防关闭后提交，带超时调度；
- **RimeApi**：全 `suspend` 接口（startup/shutdown、processKey/Bulk、commit/clear/replaceKey、
  getCommit/Context/Status、候选选删翻页、方案/选项、syncUserData、`messageFlow`），
  `SimpleRimeImpl` 把每个方法代理到 dispatcher 上执行 `RimeNative` JNI 调用；
- **RimeSession**（SDK internal，实现 `RimeEngine`）：`initialize` 在 `Dispatchers.IO` 上按序执行
  `deploySteps` → 启动引擎（超时保护）；`redeploy` 词库/配置变更后销毁重建；
  `lifecycleMutex` 串行化生命周期，`isInitialized` 为 `@Volatile`，防双重初始化与 dispatcher 泄漏；
- **消息流**：librime 通知回调 → JNI `handleRimeMessage` → `SharedFlow`（replay=1, extraBuffer=16）
  → IME 服务（方案/选项变更、部署完成后重同步）与设置/词库页（部署状态）。
  类型：`SchemaMessage` / `OptionMessage` / `DeployMessage` / `UnknownMessage`；
- **JNI 层**：`rime_jni.cc` 导出 `RimeNative_*` 函数（含热路径 `processRimeKeyBulk`）+
  静态回调 `handleRimeMessage`；`config.cc` 导出 8 个配置函数；`objconv.h` 经缓存的
  `jclass`/`jmethodID` 把 Proto 转 Java data class；静态库需 `declare_librime_module_dependencies()`
  显式注册模块符号（`rime_require_module_*`，按 WITH_* 条件编译）；
- **native 内存**：`RimeNative.trimNativeHeap()` 在部署完成后与 `TRIM_MEMORY_RUNNING_LOW` 时调用，
  归还部署残留的空闲页（真机实测 20~27MB）。

## LLM 智能续写（ai/prediction）

在引擎联想与候选栏之上的云端续写（方案文档：[docs/智能预测可行性方案.md](docs/智能预测可行性方案.md)、
[docs/联想功能优化调研与方案.md](docs/联想功能优化调研与方案.md) Phase 0~3）：

```
上屏文本 → AppContainer.commitTextObservers（开关门控）
  → LlmPredictionCoordinator.onCommitText（主线程）
      ├─ CommitWordWindow 维护最近上屏词（纯内存，随会话 reset 清空）
      ├─ ContextLruCache 命中短路（跨会话持久化：PredictionCacheStore 首需时装载/脏变更异步写）
      ├─ TriggerPolicy 决策：Trigger 即发 / Debounce 延迟发 / Skip 不发（限流 1500ms、防抖 600ms）
      ├─ 单 in-flight Job（新触发取消旧 Job）+ epoch 过期守卫（上下文已变则丢弃结果）
      ├─ RequestRateWindow 滚动一分钟配额 + FailureBackoff 指数退避（30→60→120s 封顶）
      └─ LlmPredictor.predictStream（SSE 流式，逐行增量交付 onResult 累计列表）
Service 侧 onResult（主线程）
  ├─ llmAppendAllowed 二次校验（仍处「无活跃编码」态才追加，迟到的词不允许挂新编码）
  ├─ CandidateFusion.fuse（与引擎候选去重保留引擎身份；exclude=最近上屏词防模型复读）
  └─ candidatesView.appendLlmCandidates（追加段起始 index 记录，点击分段路由）
```

- **预取/预热**：引擎预测候选渲染后预热其下一轮上下文（采纳后链式零等待）；会话开始
  （每服务实例至多一次）按缓存热度预热 3 条高频上下文，预取/预热均受
  `PowerNetworkProbe` 重请求门控（计量网络或低电量未充电时放弃）；
- **采纳闭环**：`recordAdoption` 主线程 O(1) 计数（AdoptionRecord 仅收 1~4 字纯汉字词对），
  10s 防抖落盘（AdoptionStore），`adb pull` 导出后经 `scripts/build_predict_db.py` 固化进 predict.db；
- **预测态判定**：LLM 追加窗口以「无活跃编码」（input 为空）为准而非「预测态」——句末标点
  上屏时引擎会主动清预测，这两种 menu 空的时刻恰是续写最有价值的场景；
- **隐私口径**：词窗口在 `onStartInput` / `onStartInputView` / `onFinishInputView` 三处 reset
  防跨输入框泄漏；持久化仅限候选词与脱敏词对计数；日志禁出现词窗口内容。

## 业务域模块

### 等级体系 (level/)

`LevelEngine`（:app `core/level`，纯函数计分引擎）+ `LevelRepository`（SharedPreferences 持久化，
`LevelState` 单一数据源）+ `LevelStats`（热路径入口：onCommit 仅 O(1) 原子自增，达 50 次提交/200 字
阈值或 flush 时后台异步落盘）+ `LevelActivity`（Compose 页面）。
计分：当日前 2000 字每字 1 分，2000–6000 字每字 0.5 分；等级门槛指数递增
（0/100/300/700/1400/2500/4200/6800/10500/16000）；全部脱敏聚合计数，数据仅存本机。

### 扩展词库 (dict/)

`DictModels` / `DictDownloader`（Gitee 仓库 `catalog.json` 清单，下载带进度与 sha256 校验）/
`DictManager`（安装/卸载/启停/更新；`regenerateMainDict` 重写主词库注入已启用词库，
支持白霜 FROST 与朙月 LUNA 双后端模板）/ `DictManagerViewModel` + Compose 管理界面。
供应链安全：`id` 白名单正则校验防路径穿越；`sha256` 可选校验防镜像投毒。
生效链路：变更 → `regenerateMainDict` → `RimeSdk.redeploy` 热部署。

**predict.db 联想子库**：`PredictDbManager` 管理自建/远程联想库（下载体积上限 100MB），
安装后回盖用户目录（组合根 `preDeploySteps` 在资源部署后执行 `reapplyIfInstalled`），
经引擎重部署生效；离线构建见 `scripts/build_predict_db.py`（编排指南
[docs/skills/engine/predict.db构建指南.md](docs/skills/engine/predict.db构建指南.md)）。

### 技能插件系统 (skill/ + core/skill + assets/skills)

基于 WebView 沙箱的可扩展技能面板（指南 [docs/技能插件开发指南.md](docs/技能插件开发指南.md)、
可行性 [docs/技能插件系统可行性方案.md](docs/技能插件系统可行性方案.md)）：

```
SkillManager          扫描内置（assets/skills：calculator/flowchart/poetry/weather）与
                      已安装（files/skills）技能，manifest 校验失败即不展示
SkillPackageInstaller 两段式安装：inspect（落临时文件 + 5MB/条目数/Zip Slip/manifest/冲突
                      全量校验）→ 用户确认 → commit（staging 原子替换，失败自动回滚）
SkillWebViewFactory   安全基线收口：资源全量拦截（仅放行包内相对路径）、CSP 注入、
                      DOM 存储/文件访问全关、渲染进程崩溃隔离；垫片 imeskill.js 经
                      DOCUMENT_START_SCRIPT 注入，apiVersion 由 HOST_API_VERSION 单一事实源覆写
SkillBridge           JS 单入口窄面 Bridge（__IMESkillNative.postMessage），全异步 Promise
SkillRuntime          能力实现层：权限检查、storage（串行 IO，限额 1MB）、fetch 代理
                      （HTTPS + 域名白名单 + 禁重定向 + 频控/限额）、剪贴板、输入路由
core/skill/*          纯校验逻辑：manifest/Zip 条目/版本比较/权限定义（可单测）
```

面板由 `SkillPanelCoordinator` 编排三态布局（键盘叠层 / needs_input 提升挂载 / 收缩态，
IME 窗口总高守恒）；输入路由经 `InputLogicController.CommitTarget` 抽象——面板申请焦点后
上屏文本改道注入面板输入框，Rime 编码/候选链路零改动。安全红线：技能无法读取用户在其他
应用的输入内容；渲染进程崩溃不波及 IME 主进程。

### 皮肤系统 (skin/ + core/skin)

`SkinManager`（门面：读热路径 volatile 快照 O(1) 无 IO，未命中时同步构建不含背景图的轻量快照，
后台异步补齐资源并经 `SkinChangeListener` 通知重建）/ `SkinRepository`（内置内存规格 +
导入皮肤索引与规格缓存）/ `SkinPackLoader`（`.zyskin` 安装/卸载）/ `SkinCustomizer`
（用户自定义稀疏覆盖层，按皮肤 id 独立持久化，不改写基础规格）/ `SkinSpecCodec`（skin.json 编解码）/
`SkinPreviewRenderer` / `SkinBackgroundDrawable` / `SkinAssetCache`（背景图按屏宽降采样）。
内置三套皮肤（`SkinDefaults`）：悬浮立体 `builtin.float3d`（默认）/ 云雾拟态 `builtin.yunwu`
（深浅双套）/ Material `builtin.material`；导入皮肤不得冒用 `builtin.` 保留前缀；
解锁沿用等级体系（内置按展示名查 LevelEngine 解锁表，导入皮肤默认 Lv.1）。
开发指南 [docs/自定义皮肤开发指南.md](docs/自定义皮肤开发指南.md)，样例 `skins-dev/com.ziyou.cloudcream`。

### AI 问答 / 人设 / 知识库 (ai/ + core/rag + core/ai)

双入口工具栏（`KEYCODE_AI_ASSISTANT` 问答 / `KEYCODE_AI_POLISH` 人设润色）：
`AiChatClient`（API 调用）/ `AiChatOrchestrator`（会话编排）/ `AiConfig` /
`AiPersona` + `PersonaRepository`（人设管理，`PersonaManagerActivity` 唯一 UI 入口）/
`ai/knowledge`：`KnowledgeRepository` / `KnowledgeImporter` / `KnowledgeSearcher`（BM25 检索）/
`AiMemoryStore` / `AiUsageStats`，人设润色与知识库 RAG 强绑定
（方案 [docs/AI人设润色与知识库RAG重构方案.md](docs/AI人设润色与知识库RAG重构方案.md)）。
答案支持文本/转图两种上屏路径（`commitDirectToEditor` / Commit Content API）。

### 流式语音输入 (voice/ + core/voice)

`SherpaOnnxEngine`（实现 `SpeechRecognizerEngine` 接口，基于 `app/libs/sherpa-onnx-1.13.3.aar`，
流式识别）/ `AudioCapture`（录音采集）/ `VoiceModelManager` + `VoiceModelCatalog` +
`VoiceModelDownloader`（模型按需下载与校验）。引擎经 `AppContainer.speechEngine` 懒获取，
release 后可重建；`TRIM_MEMORY_RUNNING_CRITICAL` 时主动释放数百 MB 的 native 模型。
面板 `VoicePanelCoordinator`：识别文本经 `commitDirectToEditor` 直达宿主输入框，
权限/模型未就绪时展示引导态跳转设置页（方案 [docs/实时语音输入可行性方案.md](docs/实时语音输入可行性方案.md)）。

### 应用更新 (update/ + util/AppVersionUtils)

基于蒲公英（Pgyer）API 2.0 的 in-app 更新：仅 `ZiyouApplication.onCreate` 在**主进程**触发
静默检测（24h 频控，启动延时 3s），结果暂存待前台 Activity 弹窗；键盘输入法服务不参与任何
更新逻辑。下载仅 HTTPS + 受控重定向 + 体积上限 + `.part` 原子就位；版本对比优先数字版本号，
缺失回退 versionName 逐段比较（`AppVersionUtils`，含单测）。

### 剪贴板 / 符号 / 工具栏 (data/ + core/clipboard + core/toolbar)

`ClipboardHistoryRepository`（复制即收录，去重/截断零 IO 高频路径；Android 13+ 敏感标记内容不入库；
监听收窄到键盘可见期，隐藏期复制由 onStartInputView 兜底补收）/ `SideSymbolRepository`（侧栏符号）/
`SymbolRepository`（符号键盘 YAML 分类，Service onCreate 后台预热缓存）/ `ToolbarConfig`
（功能按钮栏配置）/ `AssociationManager`（引擎级联想总开关 → `prediction` 选项）/
`UserPreferenceRepository`。

## 数据流

### 数据流 A：按键到输出（QWERTY / 普通按键）

```
1. 触摸软键盘  BaseKeyboardView 子类.onTouchEvent() → onKeyPress(keyCode, mask)
2. 回调上抛    KeyboardLayoutManager.Callbacks → Service.handleSoftKeyPress()
3. 委托管线    serviceScope.launch { inputLogic.processKey() } → InputSession（Mutex 事务）
4. 单次跨界    rime.api.processKeyBulk() → dispatcher.dispatch { RimeNative.processRimeKeyBulk() }
5. JNI 批量    process_key → [被消费则同次跨界取 commit + context] → (consumed, commit, context)
6. 上屏路由    commit → CommitTarget 解析：面板目标在场注入面板，否则 editorSink.commitText
               → InputConnection + notifyCommitObservers（commitListeners 脱敏计分 +
               commitTextObservers LLM 续写）
7. UI 更新     withContext(Main) { renderContext(result.context) }（候选/编码/侧栏/按钮栏）
```

### 数据流 B：九宫格拼音消歧（多音节组词）

```
1. 输入数字   按键 → KeyRecordStack.pushT9Key 追踪，同时发送给 Rime（T9 方案消歧）
2. 生成候选   PinyinHintProvider.buildHints(context)：优先用 T9PinYinUtils 从数字段还原
              候选拼音（含 comment 契约白名单防御与读音字典回退），回退候选 comment
3. 侧栏展示   pinyinSideBar.setPinyinCandidates(hints)
4. 用户选拼音 keyRecordStack.pushPinyinSelectAction → ReplaceCommand
              → InputSession.selectPinyin：replaceKey 原地锁定音节 + XK_End 移光标至末尾
5. 智能退格   BackSpace 且栈非空 → popAndRestore → restorePinyin（拼音段还原为数字段）
```

### 数据流 C：扩展词库 / predict.db 安装到生效

```
DictManagerViewModel.installDict → DictDownloader.downloadDict（进度 + sha256）
  → 写安装记录 → DictManager.regenerateMainDict（重写主词库）
  → RimeSdk.redeploy（销毁重建引擎，DeployState 回传 UI）
  → 重部署窗口期：EngineSyncController.awaitEngineReady 轮询等待，
    部署完成消息触发 scheduleEngineSync 重同步方案/模式
```

## 线程模型

```
┌───────────────────────────────────────────────────────────────┐
│                         UI 线程 (Main)                          │
│  • View 绘制与触摸事件、InputMethodService 生命周期回调           │
│  • commitText() 上屏、候选/键盘/侧栏/面板视图更新                 │
│  • LLM 协调器入口（onCommitText/reset/onResult 分发）            │
│  serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)│
└───────────────────────┬───────────────────────────────────────┘
                        │ suspend 调用
                        ▼
┌───────────────────────────────────────────────────────────────┐
│                    Rime 线程 (RimeDispatcher)                   │
│  • 所有 RimeNative JNI 调用、librime 内部处理（单线程串行）       │
└───────────────────────────────────────────────────────────────┘
┌───────────────────────────────────────────────────────────────┐
│                    IO 线程（多个专用池）                         │
│  • Dispatchers.IO：引擎部署/主词库重写、签到、词库下载、符号预热   │
│  • LlmPredict-IO 单线程池：候选缓存/采纳攒批的串行装载与落盘       │
│  • LevelStats.flushScope：等级计分异步落盘                       │
└───────────────────────────────────────────────────────────────┘
```

**规则**：UI 线程发起协程 → `rime.api.xxx()` 自动切 Rime 线程 → `withContext(Main)` 切回更新视图；
JNI 回调经 `SharedFlow.tryEmit()` 线程安全传递；输入热路径不产生任何磁盘 IO。

## 测试策略

纯逻辑按归属分流后可脱离 Android 运行时做 JVM 单元测试：SDK 侧覆盖引擎交互与
T9/prediction 纯逻辑，`:app` 侧覆盖业务纯逻辑与视图纯逻辑。

- **用例基线**：两工程合计见 [`scripts/unit-test-baseline.txt`](scripts/unit-test-baseline.txt)
  （当前 610，只增不减；发布脚本 `build-release.sh` 从 JUnit XML 报告累加实测值，低于基线即失败）；
- **运行**：`(cd ../ziyou-ime-sdk && ./gradlew testDebugUnitTest) && ./gradlew :app:testDebugUnitTest`
  （主工程经 AAR 消费 SDK，SDK 工程的测试需在其目录内触发）；
- **引擎替身**：涉及引擎的逻辑经 `AppContainer.overrideRimeEngine()` 注入 fake 测试；
  语音经 `overrideSpeechEngine()`；
- **守护测试**：方案文件惩罚/权重配置由 `SchemaPenaltyConfigTest` 门禁（防方案更新丢失配置）；
  更新配置默认契约由对应守护用例固化。

## 关键设计决策

### 1. 为什么单线程 Dispatcher 而非 synchronized？
| 方案 | 优点 | 缺点 |
|------|------|------|
| synchronized 锁 | 实现简单 | 可能死锁、不支持协程挂起 |
| **单线程 Dispatcher** | **协程友好、无锁、顺序保证** | 略多线程开销 |
与 Kotlin 协程天然集成，调用方使用 `suspend` 函数无感知线程切换。

### 2. 为什么键盘用 Canvas 绘制？
性能（免 XML inflate）、灵活（自定义动画/触摸反馈）、简洁（无 XML 布局）、
可控（精确控制每键尺寸位置）。基类抽象后新增布局（符号/数字/后续手写）只需继承 `BaseKeyboardView`。

### 3. 为什么九宫格要维护 KeyRecordStack？
Rime 只组织光标之前的编码片段。若把选定拼音追加到编码串末尾，会导致位置错乱与候选只剩单字。
状态机保证「列表顺序 == 编码串逻辑顺序」，选拼音时原地替换并将光标移至末尾，才能组织多音节组合候选。

### 4. 为什么引擎同步要 latest-wins + 就绪轮询？
快速布局切换与词库重部署都会并发触发引擎状态写入：latest-wins 保证「最后一次意图」生效，
消除滞后同步覆盖；就绪轮询（50ms）保证重部署窗口期不抛异常失败，部署完成消息重同步兜底。

### 5. 为什么形态切换只重建视图不动引擎？
悬浮/停靠是纯展示形态，与编码/方案/中英模式正交；仅重建视图层使切换零引擎开销、
无状态丢失风险，重建后由 `scheduleEngineSync` 把引擎状态回写到新视图。

### 6. 为什么应用启动就预热引擎？
首次安装/升版的资源部署 + 词库编译耗时数秒，若落在键盘首次弹出时刻会造成明显卡顿。
预热把重活提前到进程启动（仅 needsDeploy 或已启用时），与后续初始化幂等兼容，
失败由 IME 服务兜底，正确性不受影响。

### 7. 为什么 LLM 续写走观察者而非改动候选管线？
`commitTextObservers` 注入使续写与输入管线完全解耦：关闭时热路径仅一次内存布尔读；
结果经候选栏追加段渲染、点击走既有选词路径，Rime 编码链路零改动；
与脱敏 `commitListeners` 分列，等级链路永远不见输入内容（隐私隔离）。

### 8. 为什么引擎要接口化 + DI 容器？
调用方原先硬依赖全局单例，无法注入替身、难以测试。抽出 `RimeEngine` 接口并经
`AppContainer` 提供后，调用方依赖接口，测试可注入 fake，且为多引擎/多会话留出空间。
组合根同时承担部署步骤与上屏监听的装配，使依赖方向保持单向。

### 9. 为什么拆出独立 SDK 工程？
引擎交互与 Android/JNI 混在单模块时任何改动都触发整模块（含 NDK 链路）重编译，且引擎能力
无法被其他应用复用。独立为 `ziyou-ime-sdk` 后依赖边界由编译器强制，纯逻辑可快速 JVM 单测、
增量构建更友好，SDK 可打包 AAR 独立集成（蓝图 [docs/SDK模块拆分重构方案.md](docs/SDK模块拆分重构方案.md)）。

## 如何扩展

### 添加新的 JNI 函数
1. 在 ziyou-ime-sdk `src/main/jni/librime_jni/rime_jni.cc` 添加 `Java_com_ziyou_ime_core_RimeNative_xxx` 导出
2. 在 `RimeNative.kt` 声明对应 `@JvmStatic external fun`
3. 在 `RimeApi.kt` 增接口方法，`SimpleRimeImpl.kt` 经 `dispatcher.dispatch` 实现

### 添加新的键盘布局
1. `KeyboardType` 枚举追加一项（按需声明 `forcedSchemaId` / `allowsSchemaChoice` / `pickerLabel`，
   声明 `pickerLabel` 即自动进入键盘选择面板选单）
2. 编写 `BaseKeyboardView` 子类，提供 `rows` 与 `handleKeyUp`
3. 在 `KeyboardLayoutManager.createKeyboardView()` 登记
4. 在 `EngineSyncController.applyEngineForKeyboard` 补方案/模式同步分支

### 添加新的面板
1. 新建 `XxxPanelCoordinator`（参照现有 7 个协调器），定义 `Host` 接口
2. Service 内 `by lazy` 构造并实现匿名 Host（共享方法委托 `basePanelHost`）
3. 定义功能码加入 `KeyCode`，在 `handleSoftKeyPress` 登记（先 `closeAllPanels` 互斥）
4. `closeAllPanels` 与 `ToolbarConfig`（如需入口按钮）同步登记

### 添加新的输入方案
1. 将 `.schema.yaml` / `.dict.yaml` 放入 `assets/rime/`
2. 编辑 `default.yaml` 的 `schema_list`
3. 升 `versionCode`（触发 AssetDeployer 重部署，新增方案首次需 fullCheck 编译）

### 添加新的技能 / 皮肤
- 技能：参照 `skills-dev/` 样例与 [docs/技能插件开发指南.md](docs/技能插件开发指南.md)，
  打包 `.skill` 后经设置页安装（内置技能放入 `app/src/main/assets/skills/`）
- 皮肤：参照 `skins-dev/` 样例与 [docs/自定义皮肤开发指南.md](docs/自定义皮肤开发指南.md)，
  `pack.sh` 打包 `.zyskin` 后经设置页导入

### 启用可选 Native 模块
在 `ziyou-ime-sdk/build.gradle.kts` 添加 CMake 参数（如 `-DWITH_LUA=ON`），并经该工程内
`librime-prebuilt/build.sh` 以对应 `WITH_*=ON` 重编 librime.a（产物直接安装到 `ziyou-ime-sdk/libs/<abi>/`）。

## 构建与门禁脚本（scripts/）

| 脚本 | 用途 |
|------|------|
| `build-release.sh` | 正式发布一键脚本：JDK 探测、可选 `--rebuild-native`（重编 librime.a）、单元测试基线门禁、R8 + 签名，产物 `dist/*.apk` + sha256 |
| `build_predict_db.py` | 自建 predict.db：语料合并 + 安全过滤 + 双键扩展 + 双层召回率验收（配套 `predict_*.tsv` 语料与探针文件） |
| `fetch-sherpa-onnx.sh` | 下载语音识别 AAR（GitHub Release，备选 hf-mirror） |
| `sync_rime_frost.py` | 白霜拼音词库同步 |
| `unit-test-baseline.txt` | 单元测试用例数基线（只增不减，发布门禁比对） |
| `verify_catalog.py` | 词库 catalog 校验 |
