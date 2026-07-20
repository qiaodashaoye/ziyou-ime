# 字由输入法 架构设计文档

本文档描述字由输入法的系统架构、设计决策和各模块实现细节，面向希望深入理解或参与贡献的开发者。

## 架构概览

字由输入法采用四层架构，每层职责清晰、依赖单向向下：

```
┌─────────────────────────────────────────────────────────────┐
│                        UI 层                                 │
│  SettingsActivity · SimpleKeyboardView · SimpleCandidatesView│
├─────────────────────────────────────────────────────────────┤
│                       IME 层                                 │
│  SimpleRimeInputMethodService · KeyCode                      │
├─────────────────────────────────────────────────────────────┤
│                      Core 层                                 │
│  RimeApi · SimpleRimeImpl · RimeDispatcher · RimeSession     │
│  RimeMessage · RimeConfig · RimeConfigManager · ThemeManager │
├─────────────────────────────────────────────────────────────┤
│                      JNI 层                                  │
│  rime_jni.cc · config.cc · session.h · objconv.h            │
├─────────────────────────────────────────────────────────────┤
│                    Engine 层 (librime)                        │
│  rime_api.h (预编译静态库，C接口)                              │
└─────────────────────────────────────────────────────────────┘
```

**依赖方向**：UI → IME → Core → JNI → Engine

## 设计原则

### 1. 单线程 Dispatcher

librime 不是线程安全的。所有 Rime API 调用必须在同一个线程执行。

```
UI线程 ──┐
         ├──→ RimeDispatcher (单线程Executor) ──→ librime
协程A ───┘
```

通过 `RimeDispatcher` 封装一个 `SingleThreadExecutor`，利用 Kotlin 协程的 `withContext` 将所有 Rime 操作自动调度到专属线程。调用方只需使用 `suspend` 函数，无需关心线程安全。

### 2. RAII 资源管理

JNI 层大量使用 C++ RAII 模式避免资源泄漏：

- **SessionHolder**：自动创建/销毁 Rime 会话
- **CString**：自动 GetStringUTFChars/ReleaseStringUTFChars
- **JRef**：自动 DeleteLocalRef
- **JString**：自动创建和释放 JNI 字符串

### 3. 批量 API 调用

减少 JNI 跨界调用次数。例如 `getRimeBulkCandidates()` 一次性返回候选词列表、总数和高亮索引，避免多次跨越 JNI 边界。

### 4. 模块条件编译

通过 CMake `option()` 控制可选模块（Lua、Octagram、Predict、OpenCC）的编译链接：

```cpp
#ifdef WITH_LUA
extern void rime_require_module_lua();
#endif
```

未启用的模块不会引入任何依赖，保持最小二进制体积。

### 5. 配置最小化

仅包含 librime/data/minimal/ 的 7 个文件作为初始配置，不引入额外的方案或词典。用户可自行添加所需方案。

## 模块详细说明

### JNI 层

#### Rime 单例 (`rime_jni.cc`)

```cpp
class Rime {
  static Rime &Instance();  // 全局唯一实例
  void startup(...);        // 初始化引擎
  bool processKey(int, int); // 处理按键
  void exit();              // 释放引擎
private:
  RimeApi *rime;                     // librime C API指针
  std::shared_ptr<SessionHolder> session_; // RAII会话
};
```

关键设计：
- **单例模式**：通过 `static Rime instance` 确保引擎全局唯一
- **惰性会话**：首次调用时自动创建 `SessionHolder`，后续复用
- **环境变量传递路径**：通过 `setenv()` 将 Java 层路径传递给 librime traits

#### SessionHolder (`session.h`)

```cpp
class SessionHolder {
  SessionHolder() { id_ = api->create_session(); }
  ~SessionHolder() { api->destroy_session(id_); }
  RimeSessionId id() const;
};
```

RAII 确保会话在作用域结束时自动销毁，即使发生异常也不会泄漏。

#### 对象转换 (`objconv.h`)

负责将 C++ Proto 类型转换为 Java 对象：

```
C++ CommitProto  ──→  Java CommitProto (data class)
C++ ContextProto ──→  Java ContextProto (data class)
C++ StatusProto  ──→  Java StatusProto (data class)
```

转换过程使用缓存的 `jclass`/`jmethodID`（通过 `GlobalRefSingleton` 管理），避免重复查找。

#### 模块依赖声明

```cpp
static void declare_librime_module_dependencies() {
#ifdef WITH_LUA
  rime_require_module_lua();     // 链接Lua模块
#endif
#ifdef WITH_OCTAGRAM
  rime_require_module_octagram(); // 链接语法模块
#endif
}
```

librime 编译为静态库时，需要显式调用模块注册函数才能链接对应的符号。

### Core 层

#### RimeDispatcher 工作原理

```
┌──────────────┐     dispatch(block)     ┌─────────────────────┐
│  调用方协程   │ ────────────────────────→│  RimeDispatcher     │
│  (任意线程)   │                          │                     │
│              │ ←────────────────────────│  SingleThreadExecutor│
│  suspend等待  │     withContext返回      │  "RimeDispatcher-    │
└──────────────┘                          │   Thread"           │
                                          └─────────────────────┘
                                                   │
                                                   ▼
                                          ┌─────────────────────┐
                                          │  RimeNative.xxx()   │
                                          │  (JNI调用)           │
                                          └─────────────────────┘
```

核心实现：

```kotlin
class RimeDispatcher {
    private val executor = Executors.newSingleThreadExecutor { ... }
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    suspend fun <T> dispatch(block: () -> T): T {
        return withContext(dispatcher) { block() }
    }
}
```

- 使用 `AtomicBoolean` 标记关闭状态，防止关闭后继续提交任务
- 提供带超时的 `dispatchWithTimeout()`，防止单次操作阻塞过久

#### RimeApi 接口设计

```kotlin
interface RimeApi {
    // 生命周期
    suspend fun startup(sharedDir, userDir, version, fullCheck)
    suspend fun shutdown()

    // 输入处理（热路径，高频调用）
    suspend fun processKey(keycode, mask): Boolean
    suspend fun commitComposition(): Boolean
    suspend fun clearComposition()

    // 状态查询
    suspend fun getCommit(): CommitProto?
    suspend fun getContext(): ContextProto?
    suspend fun getStatus(): StatusProto?

    // 候选操作
    suspend fun selectCandidate(index, global): Boolean
    suspend fun changePage(backward): Boolean

    // 方案管理
    suspend fun getSchemaList(): List<SchemaItem>
    suspend fun selectSchema(schemaId): Boolean

    // 消息流
    val messageFlow: SharedFlow<RimeMessage>
}
```

设计要点：
- 全部使用 `suspend` 函数，天然支持异步调用
- 返回值使用 Kotlin data class，类型安全
- 消息通知使用 `SharedFlow`，支持多订阅者

#### 消息流 (SharedFlow)

```
librime通知回调
       │
       ▼
JNI notificationHandler (C++ lambda)
       │
       ▼
RimeNative.handleRimeMessage() (Java静态方法)
       │
       ▼
RimeMessageHandler._messageFlow.tryEmit()
       │
       ▼
messageFlow (SharedFlow, replay=1, extraBuffer=16)
       │
       ├──→ SimpleRimeInputMethodService (监听选项/方案变更)
       └──→ SettingsActivity (监听部署状态)
```

消息类型：
- `SchemaMessage` - 方案切换通知
- `OptionMessage` - 选项变更（如 ascii_mode）
- `DeployMessage` - 部署状态通知

### IME 层

#### InputMethodService 生命周期

```
onCreate()
  │── 初始化RimeSession（首次）
  │── 注册消息流监听
  ▼
onCreateInputView()
  │── 创建LinearLayout根容器
  │── 创建SimpleCandidatesView（顶部）
  │── 创建SimpleKeyboardView（底部）
  ▼
onStartInputView()
  │── 清除旧编码
  │── 同步ascii_mode状态
  │── 更新UI
  ▼
[用户输入中...]
  │── handleSoftKeyPress() / onKeyDown()
  │── processRimeKey() → getCommit() → commitText()
  │── updateUI() → getContext() → 刷新候选词+编码区
  ▼
onFinishInputView()
  │── 清除编码
  ▼
onDestroy()
  │── 取消协程scope
  │── 释放视图引用
```

#### 键盘视图 (SimpleKeyboardView)

使用 `Canvas` 纯代码绘制 QWERTY 键盘：
- 4行按键布局（数字行可选）
- 支持触摸按下高亮反馈
- 顶部显示编码区（preedit 文字）
- 支持中英文模式切换显示

#### 候选词视图 (SimpleCandidatesView)

水平滚动的候选词横条：
- 支持点击选词
- 支持左右翻页箭头
- 高亮当前候选词
- 根据主题动态着色

#### 按键码映射 (KeyCode)

```
Android KeyEvent.keyCode
        │
        ▼
KeyCode.androidKeyCodeToRimeKeyCode()
        │
        ▼
X11 Keysym (Rime引擎需要的格式)
```

映射规则：
- 字母键：直接使用 ASCII 码值（'a'=0x61, 'A'=0x41）
- 功能键：映射到 X11 keysym（BackSpace=0xff08, Return=0xff0d）
- 修饰键：通过 `getModifierMask()` 提取 Shift/Ctrl/Alt mask

### Config 层

#### 配置管理器 (RimeConfigManager)

封装 JNI 层的配置操作，提供安全的 Kotlin API：

```kotlin
// 便捷读取
val pageSize = RimeConfigManager.getDefaultInt("menu/page_size")
val schemaName = RimeConfigManager.getSchemaString("luna_pinyin", "schema/name")

// RAII风格资源管理
RimeConfigManager.withConfig("default") { peer ->
    RimeConfig.getRimeConfigString(peer, "some/key")
}
// peer在闭包结束后自动关闭
```

#### 主题系统 (ThemeManager)

```
ThemeManager (单例)
  │
  ├── lightTheme:  KeyboardTheme (白底深字)
  ├── darkTheme:   KeyboardTheme (深底浅字)
  └── materialTheme: KeyboardTheme (蓝色调)

KeyboardTheme (data class)
  ├── keyboardBackground: Int  (键盘背景色)
  ├── keyBackground: Int       (按键背景色)
  ├── keyTextColor: Int        (按键文字色)
  ├── keyPressedBackground: Int (按下背景色)
  ├── candidateBackground: Int  (候选栏背景)
  ├── candidateTextColor: Int   (候选文字色)
  ├── candidateHighlightColor: Int (高亮色)
  ├── preeditTextColor: Int     (编码区文字色)
  └── borderColor: Int          (边框色)
```

主题持久化：通过 SharedPreferences 保存用户选择。

#### 资源部署器 (AssetDeployer)

```
应用首次启动 / 版本升级
        │
        ▼
deployIfNeeded(context)
        │
        ├── 比较 currentVersionCode vs deployedVersionCode
        │
        ▼ (版本不同)
performDeploy()
        │
        ├── 创建 {filesDir}/rime/ 目录
        ├── 创建 {filesDir}/rime_user/ 目录
        ├── 递归复制 assets/rime/* → {filesDir}/rime/
        └── 保存版本号到 SharedPreferences
```

## 数据流：按键到输出

完整的按键处理链路：

```
┌─────────────────────────────────────────────────────────────────────┐
│ 1. 用户触摸软键盘                                                     │
│    SimpleKeyboardView.onTouchEvent()                                 │
│         │                                                            │
│         ▼                                                            │
│ 2. 回调到IME服务                                                      │
│    onKeyPress(keyCode, mask)                                         │
│    SimpleRimeInputMethodService.handleSoftKeyPress()                 │
│         │                                                            │
│         ▼                                                            │
│ 3. 协程发送按键到Rime                                                  │
│    serviceScope.launch {                                             │
│        processRimeKey(keyCode, mask)                                  │
│    }                                                                 │
│         │                                                            │
│         ▼                                                            │
│ 4. 通过Dispatcher调度到Rime线程                                        │
│    RimeSession.api.processKey(keyCode, mask)                         │
│    → SimpleRimeImpl.processKey()                                     │
│    → dispatcher.dispatch { RimeNative.processRimeKey() }             │
│         │                                                            │
│         ▼                                                            │
│ 5. JNI调用                                                           │
│    Java_...RimeNative_processRimeKey(keycode, mask)                  │
│    → Rime::Instance().processKey(keycode, mask)                      │
│    → rime->process_key(session(), keycode, mask)                     │
│         │                                                            │
│         ▼                                                            │
│ 6. librime引擎处理                                                    │
│    [词典查询/拼音解析/候选生成]                                          │
│    返回: true(已消费) / false(未消费)                                    │
│         │                                                            │
│         ▼                                                            │
│ 7. 获取结果                                                           │
│    if (consumed) {                                                    │
│        val commit = api.getCommit()  // 检查是否有提交文本              │
│        commit?.text?.let { commitText(it, 1) }  // 输出到编辑器       │
│        updateUI()  // 刷新候选词和编码区                               │
│    }                                                                 │
│         │                                                            │
│         ▼                                                            │
│ 8. UI更新（切回主线程）                                                 │
│    withContext(Dispatchers.Main) {                                    │
│        candidatesView.updateCandidates(context)                      │
│        keyboardView.updateComposition(composition)                    │
│    }                                                                 │
└─────────────────────────────────────────────────────────────────────┘
```

## 线程模型

```
┌───────────────────────────────────────────────────────────────┐
│                         UI 线程 (Main)                         │
│                                                               │
│  • View 绘制和触摸事件处理                                       │
│  • InputMethodService 生命周期回调                               │
│  • commitText() 提交文本到编辑器                                 │
│  • candidatesView/keyboardView 更新                            │
│                                                               │
│  serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)│
└───────────────────────┬───────────────────────────────────────┘
                        │ suspend调用
                        ▼
┌───────────────────────────────────────────────────────────────┐
│                    Rime 线程 (Dispatcher)                       │
│                                                               │
│  • 所有 RimeNative.xxx() JNI 调用                               │
│  • librime 内部处理（词典查询、拼音解析等）                         │
│  • 按键处理、候选词生成                                           │
│                                                               │
│  executor = Executors.newSingleThreadExecutor("RimeDispatcher-Thread")│
└───────────────────────────────────────────────────────────────┘
```

**线程交互规则**：
1. UI 线程通过 `serviceScope.launch` 发起协程
2. 协程内调用 `RimeSession.api.xxx()` 自动切换到 Rime 线程
3. 获取结果后通过 `withContext(Dispatchers.Main)` 切回 UI 线程更新视图
4. JNI 回调（消息通知）通过 `SharedFlow.tryEmit()` 线程安全地传递到订阅者

## 关键设计决策

### 1. 为什么使用单线程 Dispatcher 而非 synchronized？

| 方案 | 优点 | 缺点 |
|------|------|------|
| synchronized锁 | 实现简单 | 可能死锁、不支持协程挂起 |
| **单线程Dispatcher** | **协程友好、无锁、顺序保证** | 略多线程开销 |
| Actor模型 | 消息驱动 | 过度设计 |

选择 Dispatcher 因为它与 Kotlin 协程天然集成，调用方使用 `suspend` 函数无感知线程切换。

### 2. 为什么 JNI 层使用单例而非 Session-per-request？

librime 设计为进程内单例引擎。多个 Session 共享同一个引擎状态。字由输入法简化为一个共享 Session：
- 输入法场景下同一时间只有一个活跃输入焦点
- 减少 Session 创建/销毁的开销
- Session 通过 `shared_ptr` 引用计数，在 `sync()` 或 `exit()` 时自动销毁重建

### 3. 为什么键盘用 Canvas 绘制而非 XML 布局？

- **性能**：避免 XML inflate 开销，直接绘制更高效
- **灵活性**：便于实现自定义动画和触摸反馈
- **简洁性**：无需维护复杂的 XML 布局文件
- **可控性**：精确控制每个按键的尺寸和位置

### 4. 为什么消息使用 SharedFlow 而非 Callback？

- 支持多订阅者（IME服务 + 设置页面同时监听）
- `replay = 1` 确保新订阅者能收到最近一条消息
- `extraBufferCapacity = 16` 防止背压导致消息丢失
- 协程原生支持，无需手动线程切换

### 5. 为什么资源部署使用版本号对比？

- 避免每次启动都复制大文件（如 essay.txt 约 3.7MB）
- 升级时自动触发重新部署
- 通过 SharedPreferences 持久化已部署版本号，成本极低

## 如何扩展

### 添加新的 JNI 函数

1. 在 `rime_jni.cc` 中添加 JNI 导出函数：

```cpp
extern "C" JNIEXPORT jstring JNICALL
Java_com_ziyou_ime_core_RimeNative_yourNewMethod(
    JNIEnv *env, jclass clazz, /* params */) {
    // 实现
}
```

2. 在 `RimeNative.kt` 中声明对应的 `external` 方法：

```kotlin
@JvmStatic
external fun yourNewMethod(/* params */): String?
```

3. 在 `RimeApi.kt` 接口中添加方法，在 `SimpleRimeImpl.kt` 中实现。

### 添加新的输入方案

1. 将 `.schema.yaml` 和 `.dict.yaml` 放入 `assets/rime/`
2. 编辑 `default.yaml` 的 `schema_list`
3. 重新编译部署

### 添加新的键盘布局

在 `SimpleKeyboardView` 中：
1. 定义新的按键布局数组
2. 添加布局切换逻辑
3. 在 `onDraw()` 中根据当前布局绘制对应按键

### 启用可选模块

在 `app/build.gradle.kts` 中添加 CMake 参数：

```kotlin
externalNativeBuild {
    cmake {
        arguments("-DWITH_LUA=ON")
    }
}
```

同时需要将对应模块的静态库（如 `librime-lua.a`）放入 `libs/{abi}/` 目录。

## API 参考

### RimeApi（核心引擎接口）

| 方法 | 说明 | 返回值 |
|------|------|--------|
| `startup()` | 启动引擎 | Unit |
| `shutdown()` | 关闭引擎 | Unit |
| `processKey(keycode, mask)` | 处理按键 | Boolean(是否消费) |
| `getCommit()` | 获取提交文本 | CommitProto? |
| `getContext()` | 获取输入上下文 | ContextProto? |
| `getStatus()` | 获取引擎状态 | StatusProto? |
| `getCandidates(start, limit)` | 获取候选词 | List\<CandidateProto\> |
| `selectCandidate(index)` | 选择候选词 | Boolean |
| `changePage(backward)` | 翻页 | Boolean |
| `getSchemaList()` | 获取方案列表 | List\<SchemaItem\> |
| `selectSchema(id)` | 切换方案 | Boolean |
| `setOption(key, value)` | 设置选项 | Unit |
| `getOption(key)` | 获取选项 | Boolean |
| `syncUserData()` | 同步数据 | Boolean |

### RimeSession（会话管理）

| 方法 | 说明 |
|------|------|
| `initialize(context, fullCheck)` | 初始化引擎会话 |
| `destroy()` | 销毁会话释放资源 |
| `api` | 获取 RimeApi 实例 |
| `messageFlow` | 获取消息 SharedFlow |
| `initialized` | 是否已初始化 |

### RimeConfigManager（配置管理）

| 方法 | 说明 |
|------|------|
| `getDefaultInt(key)` | 读取default.yaml整数 |
| `getDefaultString(key)` | 读取default.yaml字符串 |
| `getSchemaInt(schemaId, key)` | 读取方案配置整数 |
| `getSchemaString(schemaId, key)` | 读取方案配置字符串 |
| `withConfig(id, block)` | RAII方式使用配置 |
| `withSchema(id, block)` | RAII方式使用方案配置 |

### ThemeManager（主题管理）

| 方法 | 说明 |
|------|------|
| `getCurrentTheme(context)` | 获取当前主题 |
| `setTheme(context, name)` | 设置主题 |
| `getAllThemes()` | 获取所有主题 |
| `getThemeByName(name)` | 按名称获取主题 |
