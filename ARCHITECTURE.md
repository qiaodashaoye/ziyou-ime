# 字由输入法 架构设计文档

本文档描述字由输入法的系统架构、设计决策与各模块实现细节，面向希望深入理解或参与贡献的开发者。
概览与使用说明见 [README.md](README.md)。

## 架构概览

字由输入法按 **Gradle 多模块** 组织：`:app`（Android 应用 + JNI/引擎集成 + UI + 业务域持久化）
与 `:core-logic`（纯 Kotlin 逻辑库，无 Android UI / 无 JNI 依赖）。依赖方向 `:app → :core-logic` 单向、
由编译器强制。`:app` 内部沿用经典的 **五层引擎栈**（UI → IME → Core → JNI → Engine），
并横向扩展四个**业务域**（等级体系、扩展词库、九宫格 T9 输入、技能插件），可复测的纯逻辑下沉到 `:core-logic`：

```
┌───────────────────────────── :app 模块 ──────────────────────────────┐
│                              UI 层                                     │
│  SettingsActivity(传统View)  ·  LevelActivity / DictManagerActivity   │
│  SkillManagerActivity / SkillDevGuideActivity(技能插件管理/开发指南)    │
│                              (Jetpack Compose Material3)               │
├──────────────────────────────────────────────────────────────────────┤
│                              IME 层                                     │
│  ZiYouInputMethodService（生命周期 + 视图装配，职责持续外移）        │
│   ├ InputLogicController（Rime 交互 / 上屏监听 / 刷新 UI）              │
│   ├ KeyboardLayoutManager（键盘视图装载 / 复合布局组装）               │
│   ├ PinyinHintProvider（九宫格拼音提示/预览，纯逻辑）                   │
│   ├ DisplayModeController（停靠/悬浮形态解析、切换与悬浮 insets）      │
│   └ SkillPanelCoordinator（技能面板生命周期与三态布局编排）           │
│  键盘视图: BaseKeyboardView → QwertyKeyboardView / NineGridKeyboardView │
│  候选/编码: SimpleCandidatesView · PreeditOverlayView · PinyinSideBar   │
│  面板容器: SkillPanelContainer(WebView) · FloatingPanelContainer(悬浮)  │
├──────────────────────────────────────────────────────────────────────┤
│                          Core 层 + DI                                  │
│  RimeEngine(接口) ← RimeSession(实现)   ·   di/AppContainer(组合根)     │
│  RimeDeployStep(部署步骤抽象，组合根装配，反转对业务域的依赖)         │
│  RimeApi · SimpleRimeImpl · RimeDispatcher                             │
│  RimeMessage · RimeConfig · RimeConfigManager · ThemeManager           │
├──────────────────────────────────────────────────────────────────────┤
│                              JNI 层                                     │
│  rime_jni.cc · config.cc · session.h · objconv.h · jni-utils.h         │
├──────────────────────────────────────────────────────────────────────┤
│                        Engine 层 (librime)                             │
│  rime_api.h（预编译静态库，C 接口）                                     │
└──────────────────────────────────────────────────────────────────────┘
                                 │ 依赖（单向）
                                 ▼
┌─────────────────────────── :core-logic 模块 ─────────────────────────┐
│  纯逻辑（无 Android/JNI 依赖，可独立单元测试）                          │
│  util/T9PinYinUtils(双向映射) · core/t9/KeyRecordStack(T9 状态机)       │
│  core/level/LevelEngine(等级计分纯引擎)                                 │
│  core/skill/*(manifest 校验 · Zip 安全 · 版本比较 · 权限定义)            │
│  core/floating/FloatingPanelGeometry(悬浮几何) · core/markdown(轻渲染)   │
└──────────────────────────────────────────────────────────────────────┘

      横向业务域（:app 内，复用引擎栈，通过 IME 层与 UI 层接入）
┌────────────────────┬────────────────────┬───────────────────────────┐
│    level/ 等级体系   │   dict/ 扩展词库     │  九宫格支撑                 │
│  LevelRepository    │  DictManager        │  KeyRecordStack*(状态机)    │
│  LevelStats(热路径)  │  DictDownloader     │  T9PinYinUtils*(双向映射)   │
│  LevelEngine*(计分)  │  DictModels         │  SideSymbolRepository       │
└────────────────────┴────────────────────┴───────────────────────────┘
      *标注项已下沉到 :core-logic 模块（纯逻辑）；第四个业务域 skill/ 技能插件
      （SkillManager · SkillBridge · SkillRuntime · SkillPackageInstaller ·
      SkillWebViewFactory）见下文「技能插件系统」一节，其纯校验逻辑位于 :core-logic 的 core/skill
```

**依赖方向**：`:app` → `:core-logic`（单向）；`:app` 内 UI → IME → Core → JNI → Engine（单向向下）。
业务域由 IME 层（输入热路径）与 UI 层（管理界面）调用，纯逻辑经 `:core-logic` 复用，持久化与引擎调用仍落在 `:app`。

## 设计原则

### 1. 单线程 Dispatcher
librime 不是线程安全的，所有 Rime API 调用必须在同一线程执行。`RimeDispatcher` 封装一个
`SingleThreadExecutor`，借助 `withContext` 将所有 Rime 操作自动调度到专属线程，调用方只用 `suspend` 函数，无需关心线程安全。

> **输入事务串行化**：`RimeDispatcher` 仅保证**单次** `dispatch` 原子，不保证「一次按键 = `processKey`→`getCommit`→`getContext`」
> 内的多次调用连续执行。快速连击时不同按键的调用可能在 Rime 线程上交错，导致 commit/context 与按键错配（偶发丢字/候选错乱）。
> `InputLogicController` 用一个 `Mutex`（`inputMutex`）把每个输入操作整体串行化，Kotlin `Mutex` 公平排队天然保持按键先后顺序。

### 2. RAII 资源管理
JNI 层使用 C++ RAII 避免资源泄漏：`SessionHolder`（会话）、`CString`（UTF 字符）、`JRef`（LocalRef）、`JString`（JNI 字符串）。

### 3. 批量 API 调用
减少 JNI 跨界次数。`getRimeBulkCandidates()` 一次返回候选词列表、总数与高亮索引；
`processRimeKeyBulk()` 把按键热路径的 `processKey + getCommit + getContext` 合并为单次跨界，
一次按键仅 1 次主线程↔Rime 线程往返与 1 次 JNI 调用（原为 3 次）。

### 4. 模块条件编译
通过 CMake `option()` 控制可选模块（Lua、Octagram、Predict、OpenCC）的编译链接，未启用模块不引入依赖，保持最小二进制体积。

### 5. 视图职责分离
编码区（preedit）、候选词列表、拼音侧栏拆分为独立 View（`PreeditOverlayView` / `SimpleCandidatesView` / `PinyinSideBarView`），
通过垂直/横向布局组合，各自内聚、互不干扰。键盘视图统一由 `BaseKeyboardView` 抽象绘制/触摸/主题，新增布局只需继承子类。

### 6. 热路径零磁盘 IO
输入热路径（`onCommit`）仅做 O(1) 内存自增，达阈值或生命周期节点才后台异步落盘，避免频繁 IO 影响输入流畅度（见等级体系）。

### 7. 引擎接口化 + 依赖注入
引擎能力抽象为 `RimeEngine`（生命周期）与 `RimeApi`（操作）两个接口，生产实现分别为 `RimeSession`（`object`）与 `SimpleRimeImpl`。
调用方经 `di/AppContainer`（组合根）获取 `RimeEngine`，依赖接口而非全局单例；测试可用 `overrideRimeEngine()` 注入替身。
组合根另承担两项装配，使依赖方向保持单向：
- `RimeSession.deploySteps`：引擎启动前的部署步骤（`RimeDeployStep`：资源部署 → 扩展词库注入），
  daemon 层不再直接 import config/dict 业务模块；
- `commitListeners`：编辑器路径上屏后的横切监听（如等级计分），输入热路径（InputLogicController）
  不硬编码业务单例，回调参数仅为脱敏码点数。

### 8. 纯逻辑模块化
无 Android/JNI 依赖的纯逻辑（T9 映射、九宫格状态机、等级计分）下沉到独立 `:core-logic` 模块。
依赖方向 `:app → :core-logic` 由编译器强制，纯逻辑得以脱离 Android 运行时做快速单元测试。

## 模块详细说明

### JNI 层

#### Rime 单例 (`rime_jni.cc`)
```cpp
class Rime {
  static Rime &Instance();          // 全局唯一实例
  void startup(...);                // 初始化引擎（经 setenv 传递路径给 traits）
  bool processKey(int, int);        // 处理按键
  void exit();                      // 释放引擎
private:
  RimeApi *rime;                            // librime C API 指针
  std::shared_ptr<SessionHolder> session_;  // RAII 会话
};
```
- **单例模式**：确保引擎全局唯一
- **惰性会话**：首次调用时创建 `SessionHolder`，后续复用
- **环境变量传递路径**：`setenv()` 将 Java 层路径传递给 librime traits

`rime_jni.cc` 共导出 21 个 `RimeNative_*` JNI 函数（对应 `RimeNative.kt` 的 21 个 `external` 声明，
含热路径批量函数 `processRimeKeyBulk`），
另有一个静态回调 `handleRimeMessage` 用于 librime 通知回传。

#### SessionHolder (`session.h`)
```cpp
class SessionHolder {
  SessionHolder() { id_ = api->create_session(); }
  ~SessionHolder() { api->destroy_session(id_); }
  RimeSessionId id() const;
};
```
RAII 确保会话在作用域结束时自动销毁，即使异常也不泄漏。

#### 对象转换 (`objconv.h`)
将 C++ Proto 类型转换为 Java data class（`CommitProto` / `ContextProto` / `StatusProto` / `CandidateProto` 等），
使用缓存的 `jclass`/`jmethodID`（`GlobalRefSingleton` 管理）避免重复查找。

#### 配置操作 (`config.cc`)
导出 8 个 `RimeConfig_*` 函数：`openRimeConfig` / `openRimeSchema` / `openRimeUserConfig` /
`getRimeConfigInt` / `getRimeConfigString` / `getRimeConfigListItemPath` / `setRimeConfigBool` / `closeRimeConfig`。

#### 模块依赖声明
librime 编译为静态库时，需显式调用模块注册函数才能链接对应符号：
```cpp
static void declare_librime_module_dependencies() {
#ifdef WITH_LUA
  rime_require_module_lua();
#endif
#ifdef WITH_OCTAGRAM
  rime_require_module_octagram();
#endif
}
```

### Core 层

#### RimeDispatcher
```kotlin
class RimeDispatcher {
    private val executor = Executors.newSingleThreadExecutor { /* "RimeDispatcher-Thread" */ }
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    suspend fun <T> dispatch(block: () -> T): T = withContext(dispatcher) { block() }
}
```
用 `AtomicBoolean` 标记关闭状态防止关闭后继续提交；提供带超时的调度以防单次操作阻塞过久。

#### RimeApi 接口（`suspend` 全异步）
```kotlin
interface RimeApi {
    // 生命周期
    suspend fun startup(sharedDir, userDir, version, fullCheck); suspend fun shutdown()
    // 输入处理（热路径）
    suspend fun processKey(keycode, mask): Boolean
    suspend fun processKeyBulk(keycode, mask): KeyEventResult   // 单次跨界返回 (consumed, commit, context)
    suspend fun commitComposition(): Boolean; suspend fun clearComposition()
    suspend fun replaceKey(caretPos, length, replacement): Boolean   // 九宫格拼音消歧关键
    // 状态查询
    suspend fun getCommit(): CommitProto?; suspend fun getContext(): ContextProto?; suspend fun getStatus(): StatusProto?
    // 候选操作
    suspend fun getCandidates(startIndex, limit): List<CandidateProto>
    suspend fun selectCandidate(index, global): Boolean; suspend fun deleteCandidate(index, global): Boolean
    suspend fun changePage(backward): Boolean
    // 方案 / 选项
    suspend fun getSchemaList(): List<SchemaItem>; suspend fun getCurrentSchema(): String; suspend fun selectSchema(schemaId): Boolean
    suspend fun setOption(key, value); suspend fun getOption(key): Boolean
    suspend fun syncUserData(): Boolean
    // 消息流
    val messageFlow: SharedFlow<RimeMessage>
}
```
`SimpleRimeImpl` 是其实现，把每个方法代理到 `RimeDispatcher` 上执行对应的 `RimeNative` JNI 调用。

#### RimeEngine 接口 + AppContainer（DI 组合根）
`daemon/RimeEngine.kt` 将引擎生命周期抽象为接口（`api` / `messageFlow` / `initialized` / `initialize` / `redeploy` / `destroy`）。
`di/AppContainer` 作为轻量 DI 容器（组合根）暴露 `rimeEngine: RimeEngine`，默认生产实现为 `RimeSession`，
并提供 `overrideRimeEngine()` 供测试注入替身。IME 服务经 `AppContainer.rimeEngine` 使用引擎，依赖接口而非单例。

#### RimeSession（RimeEngine 生产实现，生命周期单例）
`daemon/RimeSession.kt`（`object`，实现 `RimeEngine`）统一管理引擎生命周期，供 IME 服务与设置页共用，避免双重初始化：
- `initialize(context, fullCheck)`：**在 `Dispatchers.IO` 上**按序执行 `deploySteps`（由组合根装配：
  资源部署 `AssetDeployer` → 注入扩展词库 `DictManager.regenerateMainDict`，daemon 层不直接依赖二者）
  → 启动引擎（带超时保护），避免阻塞主线程
- `redeploy(context)`：词库变更后重新部署引擎使新词库生效
- `destroy()`：销毁会话；`api`：`RimeApi` 实例；`messageFlow`：消息流；`initialized`：是否已初始化
- **生命周期互斥**：`initialize`/`destroy`/`redeploy` 由 `lifecycleMutex` 串行化，`isInitialized` 标记为 `@Volatile`；
  防止并发调用（如 IME 服务 `onCreate` 与设置页同时触发）穿过初始化守卫导致双重初始化与 dispatcher/线程泄漏。
  实际逻辑抽为不加锁的 `doInitialize`/`doDestroy`，供已持锁的 `redeploy` 复用以规避重入死锁。

#### 消息流 (SharedFlow)
```
librime 通知回调
   → JNI notificationHandler (C++ lambda)
   → RimeNative.handleRimeMessage() (Java 静态方法)
   → _messageFlow.tryEmit()
   → messageFlow (SharedFlow, replay=1, extraBuffer=16)
       ├─→ ZiYouInputMethodService（监听 ascii_mode 等选项/方案变更）
       └─→ 设置页/词库页（监听部署状态）
```
消息类型：`SchemaMessage`（方案切换）、`OptionMessage`（选项变更，如 ascii_mode）、`DeployMessage`（部署状态）、`UnknownMessage`。

#### 配置与主题
- `RimeConfigManager`：封装 JNI 配置操作，提供 `getDefaultInt/String`、`getSchemaInt/String` 及 RAII 风格 `withConfig/withSchema`
- `SkinManager`（`skin/`）：皮肤系统门面，内置悬浮立体（默认）/ 云雾拟态 / Material 三套皮肤，支持导入 `.zyskin` 皮肤包，SharedPreferences 持久化用户选择
- `AssetDeployer`：首次启动/版本升级时把 `assets/rime/*` 递归复制到 `{filesDir}/rime/`，通过版本号对比避免重复复制

### IME 层

#### ZiYouInputMethodService 生命周期
```
onCreate()
  ├─ LevelStats.init(applicationContext)         # 等级计分初始化
  ├─ serviceScope.launch { RimeSession.initialize(fullCheck=needsDeploy) }
  └─ 监听 RimeSession.messageFlow
onCreateInputView()
  ├─ 根容器(垂直 LinearLayout)
  ├─ 候选容器: PreeditOverlayView(顶) + SimpleCandidatesView(下)
  └─ 键盘容器(FrameLayout) → 按上次保存的 KeyboardType 装载键盘
onStartInputView()
  ├─ clearComposition() + applyEngineForKeyboard(当前键盘类型)
  └─ LevelRepository.checkInToday()（后台线程，每日签到，幂等）
[输入中...] handleSoftKeyPress()/onKeyDown() → InputLogicController.processKey() →（引擎交互/上屏计分）→ renderContext()
onFinishInputView()
  ├─ keyboardView.resetInputState() + 清空编码区
  ├─ LevelStats.flush()（主动落盘计分）
  └─ clearComposition()
onDestroy()
  ├─ LevelStats.flush()
  ├─ serviceScope.cancel()
  └─ 释放视图引用
```

#### 输入协作类（从 Service 拆分）
为使 `ZiYouInputMethodService` 聚焦「Android 生命周期 + 视图装配」，输入/形态/面板职责拆分为五个协作类：
- **InputLogicController**（`ime/InputLogicController.kt`）：核心输入路径。持有 `RimeEngine`、协程作用域、共享的 `KeyRecordStack`
  与组合根注入的 `commitListeners`，负责 `processKey`（经 `processKeyBulk` 单次跨界）/ `selectCandidate` / `changePage` /
  `selectPinyin` / `restorePinyin` / `commitSideSymbol` 与 `CommitTarget` 上屏路由；
  通过 `Callbacks` 反向获取 `InputConnection`，并回调 Service 的 `renderContext` 在主线程刷新视图。
- **KeyboardLayoutManager**（`ime/KeyboardLayoutManager.kt`）：键盘视图创建与九宫格「侧栏 + 三行网格 + 全宽底栏」复合布局组装；
  经 `Callbacks` 上抛按键/切换/侧栏等交互，`install()` 返回构造好的视图引用供 Service 持有。
- **PinyinHintProvider**（`ime/PinyinHintProvider.kt`）：九宫格拼音候选与编码区预览的纯逻辑（依赖 `ContextProto` 与 `T9PinYinUtils`），可独立单测。
- **DisplayModeController**（`ime/DisplayModeController.kt`）：停靠/悬浮形态解析（手动覆盖 > 总开关 > 横屏自动）、
  形态切换与悬浮窗口 insets（几何计算委托 :core-logic 的 `FloatingPanelGeometry`）；经 `Host` 回调 Service 重建视图与重同步引擎。
- **SkillPanelCoordinator**（`ime/SkillPanelCoordinator.kt`）：技能面板生命周期（打开/关闭/WebView 释放）与三态布局编排
  （键盘叠层 / 提升挂载 / 收缩态，高度守恒）；经 `Host` 访问容器引用并切换 `commitTarget` 输入路由。

`ZiYouInputMethodService` 只保留生命周期回调、视图容器装配、方案/模式切换与消息处理（具体行数随演进变化，不在此记录）。

#### 键盘视图体系
- **BaseKeyboardView**（抽象基类）：基于「行 × 相对宽度」的布局模型，负责 Canvas 绘制（背景/圆角/阴影/文字）、
  触摸高亮+触觉反馈、主题着色、统一回调（`onKeyPress` / `onSwitchKeyboard` / `onComposingPreview` / `onSwitchToQwertyEnglish`）。
  子类只需提供 `rows` 布局与 `handleKeyUp`。
- **QwertyKeyboardView**（QWERTY）：标准 4 行，第二行半键缩进，Shift 三态（OFF/ONCE/LOCKED）大小写，一键切九宫格。
- **NineGridKeyboardView**（九宫格 T9）：`keyHeightMultiplier=1.3`，前三行为 3×3 数字键网格 + 右侧功能列
  （退格/重输/符）；字母键单击发送对应数字键给 Rime 由 T9 方案消歧，「重输」发送 Escape。
- **NineGridBottomBarView**：从九宫格剥离的**全宽底栏**（中英转换/中数切换/0/空格/回车），
  通过 `forcedUnitWidth` 与上方网格按键宽度对齐，使侧栏高度仅匹配上方三行、底栏横跨全宽。

#### 候选/编码/侧栏视图
- **PreeditOverlayView**：独立编码区，位于候选词列表上方；空值规范 —— null/空串置 GONE 不占位，有效文本置 VISIBLE。
- **SimpleCandidatesView**：水平滚动候选词横条，支持点击选词、滑动翻页、高亮当前候选。
- **PinyinSideBarView**：九宫格左侧竖排侧栏，两种模式 —— 有候选拼音时展示可点击拼音（点击锁定音节），
  无候选时展示自定义符号 + 「＋」页脚（进入符号管理）。

#### 键盘布局管理
```
createKeyboardView(type):  QWERTY→QwertyKeyboardView, NINE_GRID→NineGridKeyboardView（已移入 KeyboardLayoutManager）
installKeyboard(type):     委托 KeyboardLayoutManager.install() 组装视图（九宫格 [侧栏 : 网格 ≈ 18:82] + 全宽底栏），并回收其返回的视图引用
switchKeyboard(type):      重建视图 + 保存偏好 + 清预览 + 异步 applyEngineForKeyboard
applyEngineForKeyboard:    九宫格→切 t9 方案并保证中文模式；QWERTY→恢复进入前方案，处理 pendingEnglishMode
```

#### 按键码映射 (KeyCode)
```
Android KeyEvent.keyCode → KeyCode.androidKeyCodeToRimeKeyCode() → X11 Keysym
```
- 字母键：ASCII 码值（'a'=0x61）；功能键：X11 keysym（BackSpace=0xff08, Return=0xff0d）
- 修饰键：`getModifierMask()` 提取 Shift/Ctrl/Alt mask
- 另定义自定义功能码：`KEYCODE_SWITCH_LANGUAGE`（中英）、`KEYCODE_SWITCH_NUMBER_MODE`（中数转换，切数字键盘）、
  `KEYCODE_SWITCH_KEYBOARD`（切键盘）、`KEYCODE_SYMBOL`（符号）

### 业务域模块

#### 等级体系 (level/ + :core-logic)
```
LevelEngine     纯函数计分引擎（无状态无 IO）；**位于 :core-logic 模块 `com.ziyou.ime.core.level`**，
                已解耦对 ThemeManager 的依赖（主题名以字面量声明）：分段计分、连续签到奖励、1–10 级门槛、主题/音效解锁规则
LevelRepository （:app，level/）SharedPreferences 持久化（LevelState 单一数据源）：accumulate 结算积分、checkInToday 幂等签到
LevelStats      （:app，level/）热路径入口：onCommit 仅 O(1) 原子自增，达 50 次提交/200 字阈值或 flush 时后台异步落盘
LevelActivity   （:app，ui/）Compose 页面：等级徽章、进度条、今日/累计统计、1–10 级路线图与权益
```
- 计分：当日前 2000 字每字 1 分，2000–6000 字每字 0.5 分（分段递减）
- 等级门槛（指数递增）：0/100/300/700/1400/2500/4200/6800/10500/16000
- 全部为脱敏聚合计数，绝不记录输入内容；IME 服务与设置页同进程共享同一份 prefs

#### 扩展词库 (dict/)
```
DictModels    数据模型：RemoteDictInfo / InstalledDictInfo / DictCatalog / DictPreview + 各状态密封类
              分类枚举 DictCategory：CLASSICAL/PROFESSIONAL/DIALECT/INTERNET/SOCIAL
DictDownloader 从 Gitee 仓库拉取 catalog.json、下载词库文件（带进度回调）、抓取远程预览
DictManager   安装/卸载/启停/检查更新；regenerateMainDict 重写 luna_pinyin.dict.yaml 注入已启用词库
DictManagerViewModel / DictManagerActivity  Compose 管理界面：分类浏览、下载、启停、更新、预览弹窗
```
- 远程源：`https://gitee.com/qiaodashaoye/ziyou-ime-dicts`（`catalog.json` 描述清单）
- 主词库组成：基础 `import_tables`（cn_dicts/8105、base、ext、tencent、others）+ 已启用扩展词库 + 大写字母/数字造词
- 生效链路：变更 → `regenerateMainDict` → `RimeSession.redeploy` 热部署
- **供应链安全**：`catalog.json` 的 `id` 在解析时以白名单正则 `^[A-Za-z0-9_-]+$` 校验（`RemoteDictInfo.isValidId`），
  杜绝恶意 `id`（如 `../../`）拼接文件名造成的路径穿越写盘；`catalog.json` 可选提供 `sha256`，
  `DictDownloader` 下载后校验，不匹配则删除并拒绝安装，防止镜像投毒/传输篡改的词库注入主词库（`sha256` 为空则跳过，向后兼容）。

#### 九宫格 T9 支撑 (:core-logic + :app)
- **T9PinYinUtils**（:core-logic，`com.ziyou.ime.util`）：T9 数字键 ↔ 拼音双向映射（`t9KeyToPinyin` 由长到短匹配去重，`pinyin2Key` O(1) 反查，
  `getT9Composition` 编码格式化）。对外统一以「数字键 2–9」为规范表示，内部转「组代表字母」查表。
- **KeyRecordStack**（:core-logic，`com.ziyou.ime.core.t9`，含 `ReplaceCommand` / `InputKey`）：九宫格输入状态机。核心不变式 —— **列表顺序 == Rime 编码串逻辑顺序**：
  键入数字追加 `T9Key`；选定拼音用 `PinyinKey` **原地替换**首个 T9Key 段（使已锁定拼音始终排在剩余数字之前）；
  智能退格解锁尾部拼音还原为数字段。返回 `ReplaceCommand(caretPos, length, replacement)` 供 `RimeApi.replaceKey`。
- **SideSymbolRepository**（:app，`com.ziyou.ime.data`）：拼音侧栏自定义符号（SharedPreferences + JSON 持久化，默认常用标点）。

#### 联想输入（引擎级，librime-predict）
联想能力由 **librime-predict** 插件提供（基于 predict.db 的下一词预测）：启用后引擎在 commit 后
把预测词写入 `context.menu`，经 `InputLogicController.updateUI` → `renderContext` 走既有候选渲染与
 Rime 选词路径，无需专用 JNI 接口；预测态（菜单非空且编码串为空）在候选栏以强调色整栏绘制区分。
- **AssociationManager**（:app，`com.ziyou.ime.data`）：联想开关（默认开），由
  `applyEngineForKeyboard` 映射为 Rime 运行时选项 `prediction`（predictor 的门控选项）。
- 应用层联想管线（AssociationPipeline / UserBigramModel 等）已按简洁性决策整体移除
  （演进记录见 [docs/联想功能重构方案.md](docs/联想功能重构方案.md) 第 11 节）。
- **当前状态：已启用** —— librime-predict 已编入预编译 librime.a（superbuild `WITH_PREDICT=ON`，
  插件源码位于 `librime-prebuilt/plugins/librime-predict`）；app CMake 同步开启
  `-DWITH_PREDICT=ON`；t9 / luna_pinyin 两套 schema 已挂 `predictor`（key_binder 前）+
  `predict_translator` + `prediction` 开关（默认开）与 `predictor` 配置节
  （max_candidates=10 / max_iterations=3）；官方 data-1.0 版 predict.db（7.2MB）随 assets
  分发，由 `AssetDeployer` 部署到用户目录（versionCode 升版触发 fullCheck 重部署）。
- **superbuild 注意项**：外部插件的 OBJECT 目标不继承 Boost:: 目标的传递头文件路径
  （模块化 Boost 无单一 include 目录），superbuild 已在 add_subdirectory(librime) 后为
  插件 objs 目标补链 Boost 使用要求，并关闭插件宿主工具（BUILD_TOOLS=OFF）。

#### 技能插件系统 (skill/ + :core-logic/core/skill + assets/skill_runtime)
基于 WebView 沙箱的可扩展技能面板（计算器/天气/星座等小工具，开发者指南见
[docs/技能插件开发指南.md](docs/技能插件开发指南.md)）：
```
SkillManager          扫描内置（assets/skills）与已安装（files/skills）技能，manifest 校验失败即不展示
SkillPackageInstaller .skill 包（zip）安装流水线：大小/条目数/Zip Slip/manifest 校验 → 用户确认 → staging 原子替换
SkillWebViewFactory   安全基线统一收口：资源全量拦截（仅放行包内相对路径）、CSP 注入、
                      DOM 存储/文件访问全关、渲染进程崩溃隔离；垫片 imeskill.js 经
                      DOCUMENT_START_SCRIPT 注入，apiVersion 由 HOST_API_VERSION 动态覆写（单一事实源）
SkillBridge           JS 单入口窄面 Bridge（__IMESkillNative.postMessage），全异步 Promise，异常全量兑底不波及 IME
SkillRuntime          能力实现层：权限检查、storage（串行 IO 线程读写，限额 1MB）、fetch 代理
                      （HTTPS + 域名白名单小写/IDN 归一化 + 禁重定向 + 频控/限额）、剪贴板、输入路由
core/skill/*          纯校验逻辑（:core-logic 可单测）：SkillManifestValidator（含 HOST_API_VERSION）、
                      ZipEntryValidator、SkillVersionComparator、SkillPermission
```
- 面板由 IME 层 `SkillPanelCoordinator` 编排三态布局（键盘叠层 / needs_input 提升挂载 / 收缩态，IME 窗口总高守恒）；
- 输入路由经 `InputLogicController.CommitTarget` 抽象：面板申请焦点后上屏文本改道注入面板输入框，
  Rime 编码/候选链路零改动；`sendText` 强制先复位路由再上屏；
- 安全红线：技能无法读取用户在其他应用的输入内容；渲染进程崩溃不波及 IME 主进程。

#### 悬浮键盘形态 (DisplayMode + FloatingPanelContainer)
停靠（DOCKED）/ 悬浮（FLOATING）两种显示形态与键盘布局正交，由 IME 层 `DisplayModeController` 管理：
- 形态解析优先级：手动覆盖（本次服务生命周期） > 悬浮总开关 > 横屏自动悬浮（`DisplayModeManager` 持久化）；
- 悬浮时内容包裹进 `FloatingPanelContainer`（拖拽/位置持久化/停靠按钮），键盘/候选/编码区统一缩放；
- 窗口 insets：内容 inset 压到容器底部（宿主应用视键盘高度为 0，游戏画面不被顶起），
  触摸区域裁剪为面板矩形、面板外穿透；几何计算下沉 :core-logic 的 `FloatingPanelGeometry`（纯逻辑可单测）；
- 全局禁用全屏提取模式（`onEvaluateFullscreenMode() = false`），横屏下保留原应用画面。

## 数据流

### 数据流 A：按键到输出（QWERTY / 普通按键）
```
1. 触摸软键盘  QwertyKeyboardView.onTouchEvent()
2. 回调 IME    onKeyPress(keyCode, mask) → handleSoftKeyPress()
3. 委托控制器  serviceScope.launch { inputLogic.processKey(keyCode, mask) }
4. 单次调度    AppContainer.rimeEngine.api.processKeyBulk() → SimpleRimeImpl → dispatcher.dispatch { RimeNative.processRimeKeyBulk() }
5. JNI 批量    Java_..._processRimeKeyBulk → process_key → [被消费则同次跨界取 commit + context] → (consumed, commit, context)
6. 引擎处理    [词典查询/拼音解析/候选生成] → true(消费)/false(未消费)
7. 取结果      if (consumed) { result.commit?.text → commitAndCount(text)；用 result.context 刷新 UI }
               else 退格→deleteSurroundingText / 可打印字符→直接提交   # 均在 InputLogicController 内
8. UI 更新     withContext(Main) { callbacks.renderContext(result.context) }（Service 刷新候选/编码/侧栏）
9. 上屏监听    commitAndCount → commitListeners（组合根注入，当前为 LevelStats.onCommit 计分，仅内存自增）
```

### 数据流 B：九宫格拼音消歧（多音节组词）
```
1. 输入数字   handleSoftKeyPress → keyRecordStack.pushT9Key('4'/'8'/'6') 追踪，同时 inputLogic.processKey 发送给 Rime
2. 生成候选   updateUI → PinyinHintProvider.buildHints(context)：优先用 T9PinYinUtils 从数字段还原候选拼音，回退候选 comment
3. 侧栏展示   pinyinSideBar.setPinyinCandidates(hints)  例如 guo/gun/huo/hun
4. 用户选拼音 handlePinyinSelect(pinyin)（Service 在主线程更新状态机）
   ├─ keyRecordStack.pushPinyinSelectAction(pinyin) → ReplaceCommand
   └─ inputLogic.selectPinyin(command)：
        ├─ engine.api.replaceKey(caretPos, length, pinyin+"'")   # 原地锁定音节
        └─ engine.api.processKey(XK_End)                          # 光标移末尾，令 Rime 组织完整组合候选
5. 智能退格   BackSpace 且栈非空 → keyRecordStack.popAndRestore() → inputLogic.restorePinyin(command)（拼音段还原为数字段）或普通退格
```

### 数据流 C：扩展词库安装到生效
```
DictManagerViewModel.installDict(info)
  → DictManager.installDict → DictDownloader.downloadDict(带进度)
  → 写 ext_dicts.json 安装记录
  → DictManager.regenerateMainDict  # 重写 luna_pinyin.dict.yaml，追加已启用词库
  → RimeSession.redeploy(context)   # 热部署，DeployState 回传 UI 提示「词库已生效」
```

## 线程模型

```
┌───────────────────────────────────────────────────────────────┐
│                         UI 线程 (Main)                          │
│  • View 绘制与触摸事件、InputMethodService 生命周期回调           │
│  • commitText() 上屏、candidatesView/keyboardView/侧栏 更新       │
│  serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)│
└───────────────────────┬───────────────────────────────────────┘
                        │ suspend 调用
                        ▼
┌───────────────────────────────────────────────────────────────┐
│                    Rime 线程 (RimeDispatcher)                   │
│  • 所有 RimeNative.xxx() JNI 调用、librime 内部处理              │
│  executor = newSingleThreadExecutor("RimeDispatcher-Thread")     │
└───────────────────────────────────────────────────────────────┘
┌───────────────────────────────────────────────────────────────┐
│                 IO 线程 (LevelStats.flushScope 等)              │
│  • 等级计分落盘、每日签到、词库下载/文件读写                       │
└───────────────────────────────────────────────────────────────┘
```
**规则**：UI 线程发起协程 → `AppContainer.rimeEngine.api.xxx()` 自动切到 Rime 线程 → `withContext(Main)` 切回更新视图；
JNI 回调经 `SharedFlow.tryEmit()` 线程安全传递；引擎初始化的资源部署/主词库重写、等级落盘与词库 IO 均走 IO 线程，不阻塞主线程与输入热路径。

## 测试策略

纯逻辑下沉到 `:core-logic` 后可脱离 Android 运行时做 JVM 单元测试；`:app` 侧对不依赖 framework 的纯逻辑也做单测。

| 模块 | 测试类 | 用例数 | 覆盖 |
|------|--------|--------|------|
| `:core-logic` | `T9PinYinUtilsTest` | 7 | T9↔拼音双向映射、去重保序、非法输入、往返一致性 |
| `:core-logic` | `KeyRecordStackTest` | 7 | 选词原地替换、多音节偏移、智能退格还原、非法/不匹配 |
| `:core-logic` | `LevelEngineTest` | 11 | 分段计分/封顶、签到奖励、等级判定与进度、权益解锁 |
| `:app` | `PinyinHintProviderTest` | 13 | 数字段还原拼音、回退 comment、预览优先高亮候选、空上下文 |

运行：`./gradlew :core-logic:testDebugUnitTest :app:testDebugUnitTest`（用例总数以 `scripts/unit-test-baseline.txt` 为准，发布脚本会比对实测值，低于基线即失败）。
引擎接口化后，涉及引擎的逻辑可通过 `AppContainer.overrideRimeEngine()` 注入 fake 实现进行测试。

## 关键设计决策

### 1. 为什么单线程 Dispatcher 而非 synchronized？
| 方案 | 优点 | 缺点 |
|------|------|------|
| synchronized 锁 | 实现简单 | 可能死锁、不支持协程挂起 |
| **单线程 Dispatcher** | **协程友好、无锁、顺序保证** | 略多线程开销 |
与 Kotlin 协程天然集成，调用方使用 `suspend` 函数无感知线程切换。

### 2. 为什么 JNI 层用单例共享 Session？
librime 设计为进程内单例引擎；输入法同一时间只有一个活跃焦点，共享一个 Session 可减少创建/销毁开销，
Session 通过 `shared_ptr` 引用计数，在 `sync()`/`exit()` 时自动销毁重建。

### 3. 为什么键盘用 Canvas 绘制？
性能（免 XML inflate）、灵活（自定义动画/触摸反馈）、简洁（无 XML 布局）、可控（精确控制每键尺寸位置）。
基类抽象后，新增布局（九宫格、后续符号/手写）只需继承 `BaseKeyboardView`。

### 4. 为什么九宫格要维护 KeyRecordStack？
Rime 只组织光标之前的编码片段。若把选定拼音追加到编码串末尾，会导致位置错乱与候选只剩单字。
状态机保证「列表顺序 == 编码串逻辑顺序」，选拼音时原地替换并将光标移至末尾，才能组织多音节组合候选。

### 5. 为什么消息用 SharedFlow？
支持多订阅者（IME + 设置/词库页）；`replay=1` 让新订阅者收到最近消息；`extraBufferCapacity=16` 防背压丢失；协程原生支持。

### 6. 为什么等级计分要内存自增 + 防抖落盘？
上屏是高频热路径，直接写盘会拖慢输入。`LevelStats` 仅做原子自增，达 50 次提交/200 字或退出输入视图时才后台异步落盘，兼顾流畅与不丢分。

### 7. 为什么引擎要接口化 + DI 容器？
`ZiYouInputMethodService`、设置页、词库页原先都硬依赖 `RimeSession` 全局单例，无法注入替身、难以测试。
抽出 `RimeEngine` 接口并经 `AppContainer` 提供后，调用方依赖接口，测试可注入 fake，且为未来多引擎/多会话留出空间。

### 8. 为什么拆出 :core-logic 模块？
纯逻辑与 Android/JNI 混在单模块时，任何改动都触发整模块（含 NDK 链路）重编译，且纯逻辑难以隔离测试。
下沉到 `:core-logic` 后，依赖边界由编译器强制（`:app → :core-logic`），纯逻辑可快速 JVM 单测，增量构建更友好。

## 如何扩展

### 添加新的 JNI 函数
1. 在 `rime_jni.cc` 添加 `Java_com_ziyou_ime_core_RimeNative_yourNewMethod` 导出函数
2. 在 `RimeNative.kt` 声明对应 `@JvmStatic external fun`
3. 在 `RimeApi.kt` 增接口方法，`SimpleRimeImpl.kt` 中经 `dispatcher.dispatch` 实现

### 添加可复用纯逻辑
无 Android/JNI 依赖的算法/状态机/计分等逻辑应放入 `:core-logic`（如 `com.ziyou.ime.util` / `core.t9` / `core.level`），
并在同模块 `src/test` 下补单元测试；`:app` 通过 `implementation(project(":core-logic"))` 依赖，切勿反向依赖。

### 添加新的键盘布局
1. 在 `KeyboardType` 枚举追加一项
2. 编写对应的 `BaseKeyboardView` 子类，提供 `rows` 与 `handleKeyUp`
3. 在 `KeyboardLayoutManager.createKeyboardView()` 登记

### 添加新的输入方案
1. 将 `.schema.yaml` / `.dict.yaml` 放入 `assets/rime/`
2. 编辑 `default.yaml` 的 `schema_list`
3. 重新编译部署（新增方案首次需 fullCheck 编译）

### 启用可选 Native 模块
在 `app/build.gradle.kts` 添加 CMake 参数（如 `-DWITH_LUA=ON`），并将对应静态库放入 `libs/<abi>/`。

## API 参考

### RimeApi（核心引擎接口）
| 方法 | 说明 | 返回值 |
|------|------|--------|
| `startup()` / `shutdown()` | 启动 / 关闭引擎 | Unit |
| `processKey(keycode, mask)` | 处理按键 | Boolean(是否消费) |
| `processKeyBulk(keycode, mask)` | 批量处理按键（热路径，单次跨界） | KeyEventResult(consumed, commit, context) |
| `commitComposition()` / `clearComposition()` | 提交 / 清除当前编码 | Boolean / Unit |
| `replaceKey(caretPos, length, replacement)` | 替换编码串片段（九宫格消歧） | Boolean |
| `getCommit()` / `getContext()` / `getStatus()` | 获取提交文本 / 上下文 / 状态 | Proto? |
| `getCandidates(start, limit)` | 获取候选词 | List\<CandidateProto\> |
| `selectCandidate(index, global)` / `deleteCandidate(index, global)` | 选择 / 删除候选 | Boolean |
| `changePage(backward)` | 翻页 | Boolean |
| `getSchemaList()` / `getCurrentSchema()` / `selectSchema(id)` | 方案列表 / 当前 / 切换 | List / String / Boolean |
| `setOption(k,v)` / `getOption(k)` | 设置 / 读取选项 | Unit / Boolean |
| `syncUserData()` | 同步用户数据 | Boolean |
| `messageFlow` | 引擎消息流 | SharedFlow\<RimeMessage\> |

### RimeEngine / RimeSession（会话管理）
> `RimeSession`（`object`）实现 `RimeEngine` 接口；调用方经 `AppContainer.rimeEngine` 获取，依赖接口而非单例。

| 方法 | 说明 |
|------|------|
| `initialize(context, fullCheck)` | 初始化引擎（IO 线程部署资源 + 重生成主词库） |
| `redeploy(context)` | 词库/配置变更后重新部署 |
| `destroy()` | 销毁会话释放资源 |
| `api` / `messageFlow` / `initialized` | 引擎接口 / 消息流 / 初始化状态 |

### RimeConfigManager（配置管理）
| 方法 | 说明 |
|------|------|
| `getDefaultInt/String(key)` | 读取 default.yaml |
| `getSchemaInt/String(schemaId, key)` | 读取方案配置 |
| `withConfig(id, block)` / `withSchema(id, block)` | RAII 方式使用配置 |

### SkinManager（皮肤管理）
| 方法 | 说明 |
|------|------|
| `getCurrentSkin(context)` | 获取当前皮肤快照（缓存命中 O(1)） |
| `setSkin(context, skinId)` | 切换皮肤（校验存在性与等级解锁） |
| `getInstalledSkins(context)` | 全部可用皮肤（内置悬浮立体/云雾拟态/Material + 导入） |

### LevelRepository / LevelEngine（等级体系）
| 方法 | 说明 |
|------|------|
| `LevelRepository.load(context)` | 读取等级状态 LevelState |
| `LevelRepository.accumulate(context, chars)` | 结算上屏字符积分 |
| `LevelRepository.checkInToday(context)` | 每日签到（幂等） |
| `LevelStats.onCommit(count)` / `flush()` | 热路径计分 / 主动落盘 |
| `LevelEngine.levelForPoints/progressInLevel/levelName` | 等级判定 / 进度 / 名称 |

### DictManager（扩展词库）
| 方法 | 说明 |
|------|------|
| `installDict / uninstallDict / setEnabled` | 安装 / 卸载 / 启停 |
| `checkUpdates(context, catalog)` | 检查可更新词库 |
| `regenerateMainDict(context)` | 重新生成主词库（注入已启用词库） |
| `readLocalDictPreview(...)` | 读取本地词库预览 |

### SkillManager（技能枚举）
| 方法 | 说明 |
|------|------|
| `listSkills(context)` | 列出全部可用技能（内置在前，manifest 非法者跳过），返回 List\<SkillInfo\> |
| `installRoot(context)` | 用户安装技能根目录 `files/skills/` |
| `SkillInfo.openResource(context, relativePath)` | 统一资源读取入口（assets / 内部存储），调用方须先经 ZipEntryValidator 校验路径 |

### SkillPackageInstaller（.skill 包安装）
> 两段式安装，保证「权限确认在落盘之前」；校验失败统一抛 `IllegalArgumentException`（message 可直接展示）。

| 方法 | 说明 |
|------|------|
| `inspect(context, source)` | 第一阶段：包体落临时文件 + 全量校验（5MB 上限/条目数/Zip Slip/manifest/冲突），返回 PendingInstall |
| `commit(context, pending)` | 第二阶段：解压到 staging → 旧版本移备份 → 原子就位，失败自动回滚 |
| `abort(pending)` | 用户取消：清理临时包体 |
| `uninstall(context, skill)` | 卸载已安装技能（内置不可卸载），并清理其 storage 数据 |

### SkillWebViewFactory（安全 WebView 工厂）
| 方法 | 说明 |
|------|------|
| `create(context, skill, bridge, onRenderProcessGone)` | 创建已完成安全基线配置的 WebView：资源全量拦截（仅放行包内相对路径）、CSP 注入、文件/内容访问全关、禁跳转、渲染进程崩溃兜底 |
| `entryUrl(skill)` | 技能入口页虚拟域名 URL（`appassets.androidplatform.net/skill/<entry>`） |

### SkillBridge / SkillRuntime（JS Bridge 与能力层）
> Bridge 只做消息搬运与线程切换（JS 经 `__IMESkillNative.postMessage` 单入口，全异步 Promise，异常全量兜底）；Runtime 承载能力实现，宿主能力经 `SkillRuntime.Host` 接口注入。

| 方法 | 说明 |
|------|------|
| `SkillBridge.postMessage(message)` | JS 侧唯一入口（@JavascriptInterface），切主线程后分发到 Runtime |
| `SkillBridge.release()` / `SkillRuntime.release()` | 面板关闭：丢弃后续消息 / 取消未完成 fetch |
| `SkillRuntime.handle(method, params, complete)` | 处理一次 API 调用，结果异步交付（fetch 走 IO 协程，storage 走串行 IO 线程） |
| `SkillRuntime.deleteStorage(context, skillId)` | 卸载时清理技能 storage 文件 |

**Bridge 支持的脚本方法**（垫片 imeskill.js 封装为 `window.IMESkill`）：

| 方法 | 说明 | 所需权限 |
|------|------|---------|
| `sendText` | 文本上屏（≤5000 字符）并关闭面板 | — |
| `getContext` / `getLocale` / `haptic` | 编辑器上下文 / 语言标签 / 震动反馈 | — |
| `ui.setTitle` / `ui.close` / `ui.setExpanded` | 面板标题（≤20 字）/ 关闭 / 输入法界面展开收缩 | — |
| `storage.get/set/remove` | 键值存储（串行 IO，限额 1MB） | storage |
| `fetch` | 宿主代理网络请求：强制 HTTPS + 域名白名单 + 超时 10s + 响应 ≤1MB + 频控 30 次/分 + 并发 ≤2 + 禁重定向 | network |
| `clipboard.read` / `clipboard.write` | 剪贴板读 / 写 | clipboard_read / clipboard_write |
| `input.requestFocus` / `input.releaseFocus` | 输入路由开关（键盘上屏改道注入面板，需 manifest 声明 needs_input） | — |
