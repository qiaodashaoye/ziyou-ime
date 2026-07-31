# 字由输入法（Ziyou IME）

字由输入法是一款基于 [Rime 输入法引擎（librime）](https://github.com/rime/librime) 的 Android 中文输入法。
它以清晰的分层架构集成了完整的 Rime 能力，并在其之上扩展了 **九宫格 T9 智能输入**、**在线扩展词库**、
**打字等级成长体系** 等特色功能，既可作为学习 Rime Android 集成的参考项目，也是一款可日常使用的输入法。

- **输入引擎**：librime（从源码交叉编译并合并为单个静态库，完全独立）
- **界面**：键盘/候选区使用纯 Canvas 绘制（传统 View）；设置内的等级页、扩展词库页使用 Jetpack Compose (Material3)
- **语言**：Kotlin + Kotlin 协程，JNI 层 C++17（RAII 资源管理）
- **架构**：Gradle 多模块（`:app` + 纯逻辑库 `:core-logic`）；五层引擎栈 + 业务域；引擎经 `RimeEngine` 接口 + 轻量 DI 容器（`AppContainer`）解耦，核心纯逻辑独立单元测试

## 特性一览

- **多输入方案**：内置朙月拼音（简体）、九宫格 T9 中文九键、仓颉五代，可在设置中自由切换
- **九宫格 T9 智能九键**：单击数字键由引擎消歧（非多击循环），左侧拼音侧栏可选择音节消歧，支持多音节组词
- **QWERTY 全键盘**：纯 Canvas 绘制，支持 Shift 三态（一次/锁定）大小写、中英切换、一键切九宫格
- **在线扩展词库**：从远程仓库下载专业/古典/方言/网络/社交等分类词库，支持预览、启用/禁用、更新、卸载，动态注入主词库并热部署
- **打字等级体系**：本地脱敏统计上屏字数，换算积分与等级（1–10 级），含连续签到奖励与等级权益路线图
- **中英 / 中数切换**：九宫格底栏一键切换英文键盘或数字直输
- **拼音侧栏符号**：九宫格无候选时侧栏展示可自定义的常用标点 / 短语，一键上屏
- **内置皮肤**：悬浮立体（默认）/ 云雾拟态 / Material，随等级解锁，另支持导入 `.zyskin` 皮肤包
- **编码区职责分离**：编码区（preedit）与候选词列表拆分为独立视图，垂直堆叠、互不干扰
- **单线程安全**：所有 Rime 调用通过专属协程 Dispatcher 顺序执行，规避 librime 非线程安全问题
- **隐私优先**：等级统计仅记录脱敏聚合计数（字数/天数），绝不记录任何输入内容，数据仅存本机
- **模块化与可测试**：纯逻辑（T9 映射、九宫格状态机、等级计分）下沉到独立 `:core-logic` 模块并覆盖单元测试；引擎接口化（`RimeEngine`）+ DI 容器便于替换与测试

## 支持的输入方案

| 方案 ID | 名称 | 说明 |
|---------|------|------|
| `luna_pinyin` | 朙月拼音·简体 | 全拼方案，词库基于 [雾凇拼音 rime-ice](https://github.com/iDvel/rime-ice) 简体词库，作为扩展词库注入的主词库 |
| `t9` | 中文九键 | 九宫格 T9 智能九键，复用朙月拼音词库，`speller/algebra` 将拼音字母派生数字写法由引擎消歧 |
| `cangjie5` | 仓颉五代 | 仓颉输入方案 |

方案清单在 [`app/src/main/assets/rime/default.yaml`](app/src/main/assets/rime/default.yaml) 的 `schema_list` 中声明。

## 环境要求

- **Android Studio**：需与 Android Gradle Plugin 9.x 兼容的较新版本
- **JDK** 17
- **Android SDK**：compileSdk 35，minSdk 24（Android 7.0），targetSdk 35
- **NDK** r26+（用于编译 JNI 层，推荐 r26c）
- **CMake** 3.22.1
- **Kotlin / AGP / Compose**：Kotlin 2.2（由 AGP 内置 Kotlin 提供）、Android Gradle Plugin 9.x、Compose BOM 2026.02.01（Material3）
- **Gradle 模块**：`:app`（应用 + JNI/引擎集成 + UI）与 `:core-logic`（纯 Kotlin 逻辑库，`com.android.library` 形态，无 Android UI / 无 JNI 依赖）

> 当前构建仅打包 `arm64-v8a` ABI（见 [`app/build.gradle.kts`](app/build.gradle.kts) 的 `abiFilters`）。
> 如需其它 ABI，请同时编译对应的 `librime.a` 并在 `abiFilters` 中追加。

## 快速开始

### 1. 克隆项目

```bash
git clone <repository-url>
cd ziyou-ime
```

### 2. 准备 librime 预编译库

项目自带 `librime-prebuilt/` 模块，可**独立**从源码交叉编译 librime 并生成所需的静态库：

```bash
cd librime-prebuilt
make librime          # 编译并把产物安装到 ../libs/
cd ..
```

该模块把 librime 及其全部依赖（boost、glog、yaml-cpp、leveldb、marisa、opencc）
从源码交叉编译并**合并为单个 `librime.a`**。编译完成后 `libs/` 目录结构：

```
libs/
├── include/
│   └── rime_api.h          # librime 头文件
└── arm64-v8a/
    └── librime.a           # arm64 静态库（已合并全部依赖）
```

> 详细步骤、可选插件与版本兼容性说明见 [`librime-prebuilt/README.md`](librime-prebuilt/README.md)。
> 若已有来自其它来源的 `librime.a` 与 `rime_api.h`，按上述目录结构放入即可，App 侧无需改动。

### 3. 编译运行

```bash
# 编译 debug 版本
./gradlew :app:assembleDebug

# 安装到设备
adb install app/build/outputs/apk/debug/app-debug.apk

# 运行单元测试（纯逻辑模块 + App）
./gradlew :core-logic:testDebugUnitTest :app:testDebugUnitTest
```

### 4. 启用输入法

1. 打开系统 **设置 → 语言和输入法 → 管理键盘**
2. 开启 **字由输入法**
3. 在任意输入框调出键盘并切换到字由输入法
4. 开始输入

## 使用方法

### 键盘切换
- **QWERTY → 九宫格**：全键盘左下角「九」键
- **九宫格 → QWERTY**：九宫格底栏「英」键（同时切换为英文直输）

### 九宫格 T9 输入
- 单击字母键（2–9）逐个输入，引擎自动在所有可能拼音组合中匹配候选
- 左侧**拼音侧栏**在有候选时显示可选拼音（如 `guo / gun / huo / hun`），点击即锁定该音节，便于多音节组词
- 无候选时侧栏显示自定义符号，点击直接上屏；点击「＋」进入符号管理
- 「重输」清空当前编码；「符」切换符号；底栏「数」在中文/数字直输间切换

### 设置页功能入口
在应用图标（设置页）中可访问：
- **输入方案**：切换 `luna_pinyin` / `t9` / `cangjie5`
- **键盘皮肤**：悬浮立体（默认）/ 云雾拟态 / Material，及导入皮肤
- **我的等级**：查看等级、今日/累计上屏字数、连续天数、等级路线图
- **拼音侧栏符号**：自定义九宫格侧栏符号
- **扩展词库**：下载/管理在线词库
- **同步用户词典**：Rime 原生用户数据同步
- **重新部署**：重新部署 Rime 配置（排查配置异常）

## 扩展词库

扩展词库通过设置页「扩展词库」进入 [`DictManagerActivity`](app/src/main/java/com/ziyou/ime/ui/DictManagerActivity.kt)（Compose 页面）管理。

- **来源**：远程仓库 `https://gitee.com/qiaodashaoye/ziyou-ime-dicts`，通过 `catalog.json` 描述词库清单
- **分类**：古典文学、专业行业、地方方言、网络流行、社交聊天
- **能力**：分类浏览、词条预览、下载安装、启用/禁用、检查更新、卸载
- **生效方式**：安装/启停后 `DictManager` 重新生成主词库 `luna_pinyin.dict.yaml`（保留基础
  `import_tables`，动态追加已启用词库），再通过 `RimeSession.redeploy()` 热部署引擎
- 词库源仓库结构见项目内 [`dicts-repo/`](dicts-repo/)（`catalog.json` + `dicts/*.dict.yaml`）

## 打字等级体系

- **计分**：上屏字符按分段递减计分（当日前 2000 字每字 1 分，2000–6000 字每字 0.5 分，超出不计）
- **签到**：每日首次使用固定奖励，连续天数额外加成
- **等级**：1–10 级，指数型递增门槛，各等级解锁皮肤等权益（悬浮立体默认、云雾拟态 Lv.2、Material Lv.7）
- **性能**：上屏热路径仅做 O(1) 内存自增，达阈值或退出输入视图时才后台异步落盘
- **隐私**：全部为脱敏聚合计数，绝不记录输入内容，数据仅存本机 SharedPreferences

## Rime 引擎集成

字由以分层方式集成 librime，并针对 Android 场景与解耦需求做了以下优化：

- **单线程调度**：librime 非线程安全，所有引擎调用经 `RimeDispatcher`（专属单线程 Executor + 协程 `withContext`）顺序执行，调用方只用 `suspend` 函数，无需关心线程安全。
- **引擎接口化 + DI**：引擎能力抽象为 `RimeEngine` 接口（生命周期：初始化/重部署/销毁）与 `RimeApi` 接口（按键/候选/选项等操作），生产实现为 `RimeSession` 单例，经 `di/AppContainer` 组合根提供。调用方依赖接口而非单例，便于替换与测试。
- **异步初始化不阻塞主线程**：资源部署、主词库重写、目录创建等磁盘 IO 统一在 `Dispatchers.IO` 执行（首次安装/升级需递归复制整包 assets），引擎启动带超时保护。
- **批量 JNI 调用**：候选查询等热路径通过批量接口一次跨界返回，减少 JNI 边界往返。
- **RAII 资源管理**：JNI 层用 C++ RAII（`SessionHolder`/`CString`/`JRef`/`JString`）确保会话与字符串资源不泄漏。
- **消息流广播**：引擎通知经 JNI 回调 → `SharedFlow` 广播给 IME 服务与设置/词库页（方案切换、选项变更、部署状态）。

更细的时序、线程模型与数据流见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 构建说明

### CMake 构建配置

JNI 层的 CMake 配置位于 [`app/src/main/jni/librime_jni/CMakeLists.txt`](app/src/main/jni/librime_jni/CMakeLists.txt)，
通过 `option()` 控制可选模块编译链接：

```cmake
option(WITH_LUA "Enable Lua module" OFF)
option(WITH_OCTAGRAM "Enable Octagram module" OFF)
option(WITH_PREDICT "Enable Predict module" OFF)
option(WITH_OPENCC "Enable OpenCC module" OFF)
```

在 `app/build.gradle.kts` 中启用可选模块：

```kotlin
android {
    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments("-DWITH_LUA=ON", "-DWITH_OPENCC=ON")
            }
        }
    }
}
```

启用某模块时，还需将对应的静态库（如 `librime-lua.a`）放入 `libs/<abi>/`。

### 模块化构建

项目按 Gradle 多模块组织，依赖方向单向向下（`:app → :core-logic`，由编译器强制边界）：

- `:app`：Android 应用，包含 JNI/引擎集成、IME 服务、UI 与业务域持久化，依赖 `:core-logic`
- `:core-logic`：纯 Kotlin 逻辑库（`com.android.library` 形态，复用 AGP 内置 Kotlin），不含任何 Android UI / JNI 依赖，承载 T9 映射、九宫格状态机、等级计分等可独立单测的纯逻辑

```bash
# 仅编译/测试纯逻辑模块
./gradlew :core-logic:testDebugUnitTest

# 编译并测试整个工程
./gradlew :core-logic:testDebugUnitTest :app:testDebugUnitTest
```

> 首次同步会拉取 `com.android.library` 插件 marker；离线环境请先在联网时同步一次。

### 编译 Release 版本

```bash
# Release 已配置 R8/ProGuard 混淆
./gradlew :app:assembleRelease
```

## 配置说明

### 配置文件目录

| 目录 | 用途 | 运行时路径 |
|------|------|-----------|
| `app/src/main/assets/rime/` | 初始配置文件（打包进 APK） | — |
| `{filesDir}/rime/` | 共享数据目录（shared_data_dir） | 部署后的方案/词库配置 |
| `{filesDir}/rime/ext_dicts/` | 扩展词库存放目录 | 已安装词库 + `ext_dicts.json` 记录 |
| `{filesDir}/rime_user/` | 用户数据目录（user_data_dir） | 用户词典等 |

### 内置资源（`assets/rime/`）

- 方案：`luna_pinyin.schema.yaml`、`t9.schema.yaml`、`cangjie5.schema.yaml`
- 主词库：`luna_pinyin.dict.yaml`（运行时会被 `DictManager` 重写以注入扩展词库）
- `cn_dicts/`：8105、base、ext、tencent、others（雾凇拼音简体基础词库）
- `cangjie5.dict.yaml`、`default.yaml`、`essay.txt`（词频）、`symbols.yaml`、`opencc/`（简繁/异体转换数据）

### 添加新的输入方案

1. 将方案文件（`.schema.yaml`）与词典文件（`.dict.yaml`）放入 `app/src/main/assets/rime/`
2. 在 `default.yaml` 的 `schema_list` 中添加：

```yaml
schema_list:
  - schema: luna_pinyin
  - schema: t9
  - schema: cangjie5
  - schema: your_new_schema   # 新增方案
```

3. 重新编译安装，或在设置页触发「重新部署」（新增/修改方案首次需 fullCheck 编译）

### 自定义配置

- 修改 `default.yaml` 调整全局选项（如候选词数量 `menu/page_size`）
- 编辑方案 `.schema.yaml` 自定义输入行为
- 通过 `RimeConfigManager` API 在运行时读写配置

## 项目结构

```
ziyou-ime/
├── app/                               # 应用模块（Android application + JNI/引擎集成 + UI）
│   └── src/main/
│       ├── assets/rime/               # Rime 配置（方案 + 词库 + opencc 数据）
│       ├── java/com/ziyou/ime/
│       │   ├── ZiyouApplication.kt        # Application 入口
│       │   ├── core/                       # 核心层：Rime 引擎交互
│       │   │   ├── RimeNative.kt          # JNI native 方法声明（20 个 external 函数）
│       │   │   ├── RimeApi.kt / SimpleRimeImpl.kt  # 引擎 suspend API 接口与实现
│       │   │   ├── RimeDispatcher.kt      # 单线程协程调度器
│       │   │   ├── ProtoTypes.kt          # 数据传输对象（data class）
│       │   │   ├── RimeMessage.kt         # 消息类型 + SharedFlow 广播
│       │   │   └── RimeConfig.kt          # 配置文件 JNI 接口
│       │   ├── daemon/                     # 引擎生命周期
│       │   │   ├── RimeEngine.kt          # 引擎抽象接口（解耦/可测试）
│       │   │   └── RimeSession.kt         # 接口实现单例（初始化/重部署/销毁）
│       │   ├── di/AppContainer.kt          # 轻量 DI 容器（组合根，提供 RimeEngine）
│       │   ├── config/                     # 配置层：RimeConfigManager / ThemeManager / AssetDeployer
│       │   ├── ime/                        # 输入法服务层
│       │   │   ├── ZiYouInputMethodService.kt  # IME 服务主类（生命周期 + 视图装配）
│       │   │   ├── InputLogicController.kt # 输入逻辑控制器（Rime 交互/上屏/刷新 UI）
│       │   │   ├── KeyboardLayoutManager.kt # 键盘视图装载器（复合布局组装）
│       │   │   ├── PinyinHintProvider.kt   # 九宫格拼音提示/预览纯逻辑
│       │   │   ├── BaseKeyboardView.kt    # 键盘视图基类（Canvas 绘制/触摸/主题）
│       │   │   ├── QwertyKeyboardView.kt  # QWERTY 全键盘
│       │   │   ├── NineGridKeyboardView.kt # 九宫格 T9 键盘 + 全宽底栏
│       │   │   ├── PinyinSideBarView.kt   # 九宫格拼音/符号侧栏
│       │   │   ├── SimpleCandidatesView.kt # 候选词横条
│       │   │   ├── PreeditOverlayView.kt  # 编码区（与候选词分离）
│       │   │   ├── KeyboardType.kt / KeyCode.kt
│       │   ├── data/SideSymbol.kt          # 侧栏符号仓库（SharedPreferences）
│       │   ├── dict/                       # 扩展词库：DictManager / DictDownloader / DictModels
│       │   ├── level/                      # 等级持久化：LevelRepository / LevelStats / LevelState
│       │   └── ui/                         # SettingsActivity / LevelActivity / DictManagerActivity(+VM)
│       ├── jni/librime_jni/               # C++/JNI 层
│       │   ├── rime_jni.cc / config.cc / session.h / jni-utils.h / objconv.h / helper-types.h
│       │   └── CMakeLists.txt            # Native 构建配置
│       └── res/                           # Android 资源（含 input_method.xml）
├── core-logic/                        # 纯逻辑模块（无 Android UI / 无 JNI 依赖，可独立单测）
│   └── src/{main,test}/java/com/ziyou/ime/
│       ├── util/T9PinYinUtils.kt          # T9 ↔ 拼音双向映射
│       ├── core/t9/KeyRecordStack.kt      # 九宫格输入状态机（+ ReplaceCommand / InputKey）
│       └── core/level/LevelEngine.kt      # 等级计分纯引擎（无状态无 IO）
├── dicts-repo/                        # 扩展词库源仓库（catalog.json + dicts/*.dict.yaml）
├── libs/                              # 预编译 librime（按 ABI 分目录，由 librime-prebuilt 生成）
├── librime-prebuilt/                  # librime 预编译模块（源码交叉编译 + 合并为 librime.a）
├── docs/                              # 设计文档（如 等级体系可行性方案）
├── ARCHITECTURE.md                    # 架构设计文档
├── build.gradle.kts / settings.gradle.kts / gradle.properties
```

更完整的架构说明、模块交互与数据流详见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 项目依赖

| 依赖 | 用途 |
|------|------|
| librime（预编译静态库） | Rime 输入引擎 |
| `:core-logic`（内部模块） | 纯逻辑：T9 映射 / 九宫格状态机 / 等级计分（可独立单测） |
| Kotlin Coroutines | 异步与单线程 Dispatcher |
| Jetpack Compose (Material3) | 等级页、扩展词库页 |
| AndroidX AppCompat / Lifecycle / ViewModel | 设置页与 ViewModel |
| AndroidX Preference | 偏好存储 |

需要 `INTERNET` 权限（用于下载扩展词库）。

## 贡献指南

欢迎提交 Pull Request 和 Issue！

### 开发流程

1. Fork 本项目
2. 创建功能分支：`git checkout -b feature/your-feature`
3. 提交更改：`git commit -m "feat: add your feature"`
4. 推送分支：`git push origin feature/your-feature`
5. 提交 Pull Request

### 代码规范

- Kotlin 代码遵循 [Kotlin 官方编码规范](https://kotlinlang.org/docs/coding-conventions.html)
- 纯逻辑（无 Android/JNI 依赖）应置于 `:core-logic` 模块，禁止反向依赖 `:app`
- C++ 代码遵循项目 `.clang-format` 配置（C++17 标准）
- 所有 Rime 调用必须通过 `RimeDispatcher` 调度（librime 非线程安全）
- JNI 层使用 RAII 管理资源，避免内存泄漏
- 提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/)

### 运行检查

```bash
# 编译检查
./gradlew :app:assembleDebug

# 单元测试（core-logic 纯逻辑 + app）
./gradlew :core-logic:testDebugUnitTest :app:testDebugUnitTest

# 代码格式化（JNI 层）
clang-format -i app/src/main/jni/librime_jni/*.cc app/src/main/jni/librime_jni/*.h
```

## 许可证

本项目基于 [GPL-3.0-or-later](LICENSE) 许可证开源。

```
SPDX-License-Identifier: GPL-3.0-or-later
```
