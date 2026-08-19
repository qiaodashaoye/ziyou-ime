# 字由输入法（Ziyou IME）

字由输入法是一款基于 [Rime 输入法引擎（librime）](https://github.com/rime/librime) 的 Android 中文输入法。
它以清晰的分层架构集成了完整的 Rime 能力，并在其之上扩展了 **九宫格 T9 智能输入**、**LLM 智能续写**、
**在线扩展词库**、**流式语音输入**、**WebView 技能插件**、**可导入皮肤**、**打字等级成长体系** 与
**悬浮/停靠双形态键盘** 等特色功能，既可作为学习 Rime Android 集成的参考项目，也是一款可日常使用的输入法。

- **输入引擎**：librime（源码交叉编译合并为单个静态库 `librime.a`，已启用 predict / witogram 插件）
- **界面**：键盘/候选区/功能面板使用纯 Canvas 绘制（传统 View）；设置内的等级页、词库页、技能页、皮肤页等使用 Jetpack Compose (Material3)
- **语言**：Kotlin 2.2 + Kotlin 协程，JNI 层 C++17（RAII 资源管理）
- **架构**：本仓 `:app` 单模块 + 隔壁独立 SDK 工程 `ziyou-ime-sdk`（坐标 `com.ziyou:ime-sdk`，本仓以本地文件 AAR 消费：`app/libs/ime-sdk-release.aar` 随仓入库，零外部仓库依赖）；引擎经 `RimeEngine` 接口 + 轻量 DI 容器（`AppContainer`）解耦，纯逻辑覆盖 JVM 单元测试

## 特性一览

### 输入核心

- **多输入方案**：内置白霜拼音（默认，含 witogram 语法模型）、朙月拼音·简体、九宫格 T9 中文九键、仓颉五代，可在设置中自由切换
- **九宫格 T9 智能九键**：单击数字键由引擎消歧（非多击循环），左侧拼音侧栏可选择音节消歧，支持多音节组词与智能退格还原
- **QWERTY 全键盘**：纯 Canvas 绘制，支持 Shift 三态（一次/锁定）大小写、中英切换、一键切九宫格
- **符号 / 数字键盘**：分类导航 + 符号网格（YAML 分类数据、后台预热缓存），数字直输临时面板，返回无感恢复原布局
- **引擎级联想**：librime-predict 插件基于 predict.db 做下一词预测，预测态候选以强调色整栏展示；predict.db 支持自建（`scripts/build_predict_db.py`）与远程下载更新
- **LLM 智能续写**：上屏文本触发云端大模型续写候选，SSE 流式交付追加到候选栏，含缓存命中短路、预测式预取、词窗口预热、限流熔断与电量/网络门控（详见下文）
- **单线程安全**：所有 Rime 调用通过专属协程 Dispatcher 顺序执行，规避 librime 非线程安全问题
- **Rime 引擎预热**：进程启动时按需后台预热引擎（资源部署 + 词库编译），键盘首次弹出时引擎已就绪

### 形态与外观

- **悬浮 / 停靠双形态**：停靠（默认）与悬浮键盘自由切换，横屏可自动悬浮（游戏场景不顶起画面、面板外触摸穿透），位置可拖拽并持久化
- **内置皮肤**：悬浮立体（默认）/ 云雾拟态 / Material，随等级解锁；支持导入 `.zyskin` 皮肤包与可视化自定义（稀疏覆盖层）
- **编码区职责分离**：编码区（preedit）与候选词列表拆分为独立视图，垂直堆叠、互不干扰；无候选时展示功能按钮栏

### 扩展生态

- **技能插件系统**：基于 WebView 沙箱的可扩展技能面板，内置计算器 / 流程图 / 诗词 / 天气，支持安装 `.skill` 包，权限声明 + 资源拦截 + CSP 注入的安全基线
- **AI 能力**：AI 问答面板与人设润色面板双入口，人设管理与知识库 RAG（BM25 检索）强绑定
- **流式语音输入**：基于 sherpa-onnx 的本地流式语音识别，模型按需下载，识别文本直达上屏
- **在线扩展词库**：从远程仓库下载专业/古典/方言/网络/社交等分类词库，支持预览、启用/禁用、更新、卸载，动态注入主词库并热部署
- **涂鸦画板 / 粘贴板历史 / 工具面板**：手绘图片发送（Commit Content API）、剪贴板历史收录与粘贴、Logo 键工具集

### 成长与隐私

- **打字等级体系**：本地脱敏统计上屏字数，换算积分与等级（1–10 级），含连续签到奖励与等级权益路线图
- **隐私优先**：等级统计仅记录脱敏聚合计数；LLM 续写词窗口随输入会话清空、持久化仅限候选词与脱敏词对计数；技能插件无法读取用户在其他应用的输入

## 支持的输入方案

| 方案 ID | 名称 | 说明 |
|---------|------|------|
| `rime_frost` | 白霜拼音 | 默认全拼方案，挂载 witogram 语法模型（`grammar/zh-moqi.gram`）提升组句质量 |
| `luna_pinyin` | 朙月拼音·简体 | 全拼方案，词库基于 [雾凇拼音 rime-ice](https://github.com/iDvel/rime-ice) 简体词库，作为扩展词库注入的主词库 |
| `t9` | 中文九键 | 九宫格 T9 智能九键，复用拼音词库，`speller/algebra` 将拼音字母派生数字写法由引擎消歧 |
| `cangjie5` | 仓颉五代 | 仓颉输入方案 |
| `melt_eng` | 英文输入法 | 辅助英文方案（自动英文输入配套） |

方案清单在 [`app/src/main/assets/rime/default.yaml`](app/src/main/assets/rime/default.yaml) 的 `schema_list` 中声明。

## 环境要求

- **Android Studio**：需与 Android Gradle Plugin 9.x 兼容的较新版本
- **JDK** 21（Gradle daemon；`gradle.properties` 已固定指向 Android Studio 自带 JBR，勿改回自动探测）
- **Android SDK**：compileSdk 37，targetSdk 36，minSdk 24（Android 7.0）
- **NDK** r26+（用于编译 JNI 层，推荐 r26c）
- **CMake** 3.22.1
- **Kotlin / AGP / Compose**：Kotlin 2.2.10、Android Gradle Plugin 9.3.1、Compose BOM 2026.06.01（Material3）、Coroutines 1.11.0（版本集中在 [`gradle/libs.versions.toml`](gradle/libs.versions.toml)）
- **Gradle 模块**：本仓仅 `:app`；底层 SDK 为隔壁独立工程 `ziyou-ime-sdk`（`com.android.library` 形态），其 release AAR 产物以**本地文件**形式置于 `app/libs/ime-sdk-release.aar`（与 sherpa-onnx 同模式，随仓入库），`implementation(files(...))` 引入——**克隆本仓后无需网络、无需构建 SDK 源码、无需配置 Maven 仓库，直接 `./gradlew :app:assembleDebug` 即可**

#### 更新 app/libs 内的 SDK AAR（开发者）

```bash
# 在 ziyou-ime-sdk 工程内重新编译并覆盖（scripts/build-release.sh 会自动执行此同步）
cd ../ziyou-ime-sdk && ./gradlew assembleRelease
cp build/outputs/aar/ime-sdk-release.aar ../ziyou-ime/app/libs/ime-sdk-release.aar
```

> AAR 内含 `jni/arm64-v8a/librime_jni.so`（librime.a 已静态链入）与 consumer
> ProGuard 规则（自动应用）；`files()` 为直接文件依赖，无需 flatDir/仓库解析。

#### SDK AAR 获取（第三方集成）

| 渠道 | 方式 | 适用场景 |
|------|------|---------|
| Maven Local | 克隆 ziyou-ime-sdk 后执行 `./gradlew publishToMavenLocal`，宿主 `repositories { mavenLocal() }` + `implementation("com.ziyou:ime-sdk:0.1.0-SNAPSHOT")` | 本地开发/联调 |
| 私有 Maven 仓库 | Nexus/Artifactory/阿里云效等上传 AAR + POM + sources.jar，宿主配仓库 URL 后同坐标引用 | 团队/正式发布 |
| GitHub Packages | `maven { url = uri("https://maven.pkg.github.com/<owner>/ziyou-ime-sdk") }`（需 token） | 开源分发 |

> 注：JitPack 不适用——其从源码即时构建，而 SDK 依赖 277MB 预编译 `librime.a`
> 与多个 git 子模块，需自行托管二进制产物。集成步骤详见 ziyou-ime-sdk 工程的
> `INTEGRATION.md`（依赖配置/初始化/API 示例/排查）。

> 当前构建默认仅打包 `arm64-v8a` ABI（见 [`app/build.gradle.kts`](app/build.gradle.kts) 的 `abiFilters`，
> 可用 `-Pziyou.abis=` 覆盖）。如需其它 ABI，请同时编译对应的 `librime.a`。

## 快速开始

### 1. 克隆项目

```bash
git clone <repository-url>
cd ziyou-ime
# SDK 为隔壁独立工程，需与本仓同级目录放置
git clone <sdk-repository-url> ../ziyou-ime-sdk
```

### 2. 准备 librime 预编译库

预编译库构建工具链 `librime-prebuilt/` 位于 ziyou-ime-sdk 工程内（产物直接安装到该工程 `libs/`，日常 App 开发通常已就位，无需重编）：

```bash
cd ../ziyou-ime-sdk/librime-prebuilt
make librime          # 编译并把产物安装到 ziyou-ime-sdk/libs/
cd ../../ziyou-ime
```

该工具链把 librime 及其全部依赖（boost、glog、yaml-cpp、leveldb、marisa、opencc）与启用的插件
（predict、witogram 等）从源码交叉编译并**合并为单个 `librime.a`**。编译完成后目录结构：

```
ziyou-ime-sdk/libs/
├── include/
│   └── rime_api.h          # librime 头文件
└── arm64-v8a/
    └── librime.a           # arm64 静态库（已合并全部依赖与插件）
```

> 详细步骤、可选插件与版本兼容性说明见 ziyou-ime-sdk 工程内 `librime-prebuilt/README.md`。

### 3. 准备语音识别依赖（可选）

语音输入依赖 `app/libs/sherpa-onnx-1.13.3.aar`（不入 git）。缺失时执行：

```bash
./scripts/fetch-sherpa-onnx.sh    # 默认走 GitHub Release，备选 hf-mirror 镜像
```

### 4. 编译运行

```bash
# 编译 debug 版本
./gradlew :app:assembleDebug

# 安装到设备
adb install app/build/outputs/apk/debug/app-debug.apk

# 运行单元测试（SDK + App；主工程经 AAR 消费 SDK，SDK 测试需在其工程目录内触发）
(cd ../ziyou-ime-sdk && ./gradlew testDebugUnitTest) && ./gradlew :app:testDebugUnitTest
```

### 5. 启用输入法

1. 打开系统 **设置 → 语言和输入法 → 管理键盘**
2. 开启 **字由输入法**
3. 在任意输入框调出键盘并切换到字由输入法
4. 首次启动会触发 Rime 资源部署与词库编译（已在进程启动时预热，见下文）

## 重点特性详解

### Rime 引擎预热机制

`ZiyouApplication.onCreate` 在进程启动时按需后台预热引擎（见
[`ZiyouApplication.kt`](app/src/main/java/com/ziyou/ime/ZiyouApplication.kt)），把耗时的资源部署与词库编译
从「键盘首次弹出」提前到「进程启动」：

- **触发条件**（满足其一）：首次安装/版本升级需要部署（`AssetDeployer.needsDeploy`）；或本输入法已被用户启用（键盘随时可能拉起，引擎保持热态）。其余进程启动不加载引擎，避免无谓的内存与电量开销。
- **幂等兼容**：与 IME 服务 `onCreate` / 设置页的初始化共用 `RimeSdk` 内部的 `lifecycleMutex + isInitialized` 守卫，不会双重初始化；预热失败不影响正确性，IME 服务会再次触发初始化兜底。

### LLM 智能续写

在引擎联想之上的云端增强（设计文档：[`docs/智能预测可行性方案.md`](docs/智能预测可行性方案.md)、
[`docs/联想功能优化调研与方案.md`](docs/联想功能优化调研与方案.md)）：

- **触发链路**：编辑器路径上屏文本经组合根注入的 `commitTextObservers` 通知 `LlmPredictionCoordinator`，
  由触发策略（Trigger/Debounce/Skip）决定是否请求云端 LLM；
- **流式交付**：SSE 流式请求逐行增量交付候选，首条候选感知延迟降至首行到达；结果经去重融合
  （`CandidateFusion`）追加到候选栏，点击走与普通候选一致的上屏路径；
- **性能优化**：上下文 LRU 缓存跨会话持久化（命中即免请求）、引擎预测候选渲染后预取下一轮上下文、
  会话开始按缓存热度预热、采纳词对攒批落盘供构建期固化进 predict.db；
- **安全与成本**：单 in-flight 请求 + epoch 过期守卫（迟到的词不挂新编码）、滚动限流与失败指数退避、
  计量网络/低电量未充电时放弃重请求（`PowerNetworkProbe`）；
- **隐私口径**：词窗口随输入会话（`onStartInput`/`onFinishInputView`）清空防跨输入框泄漏；
  持久化仅限候选词与脱敏词对计数，不含编辑器原文。

### 悬浮 / 停靠形态切换

由 IME 层 `DisplayModeController` 管理（设计文档：[`docs/游戏悬浮输入法可行性方案.md`](docs/游戏悬浮输入法可行性方案.md)）：

- **形态解析优先级**：手动覆盖（本次服务生命周期） > 悬浮总开关 > 横屏自动悬浮（`DisplayModeManager` 持久化）；
- **悬浮形态**：内容包裹进 `FloatingPanelContainer`（拖拽/位置持久化/停靠按钮），键盘/候选/编码区统一缩放；
  窗口 insets 把内容 inset 压到容器底部（宿主应用视键盘高度为 0，游戏画面不被顶起），
  触摸区域裁剪为面板矩形、面板外穿透；
- **正交性**：形态切换仅重建视图层，Rime 编码/方案/中英模式等引擎状态不受影响；
- **全屏禁用**：`onEvaluateFullscreenMode() = false`，横屏下保留原应用画面；
- 工具栏「浮」键一键切换悬浮 ↔ 停靠。

### 自定义技能与皮肤

**技能插件**（开发指南 [`docs/技能插件开发指南.md`](docs/技能插件开发指南.md)、教程
[`docs/自定义技能开发教程.md`](docs/自定义技能开发教程.md)）：

- 技能是「manifest.json + index.html + script.js」的静态包（zip 封装为 `.skill`），运行在 WebView 沙箱中；
- 内置技能位于 `app/src/main/assets/skills/`（计算器 / 流程图 / 诗词 / 天气）；
  开发样例位于 [`skills-dev/`](skills-dev/)（星座运势、诗词助手，`*.skill` 为打包产物）；
- JS 经单入口 Bridge（`window.IMESkill` 垫片）调用宿主能力：文本上屏、存储、fetch 代理、剪贴板、输入路由等，按 manifest 声明权限门控；
- 设置页「技能管理」支持安装/卸载/更新，两段式安装保证权限确认先于落盘。

**自定义皮肤**（开发指南 [`docs/自定义皮肤开发指南.md`](docs/自定义皮肤开发指南.md)）：

- 皮肤规格为 `skin.json`（颜色/尺寸/背景图，支持深浅色双套），打包为 `.zyskin`；
- 开发样例位于 [`skins-dev/com.ziyou.cloudcream`](skins-dev/com.ziyou.cloudcream/)（`pack.sh` 打包）；
- 设置页「皮肤」支持导入 `.zyskin`、可视化自定义（稀疏覆盖层，不改写基础规格）；
- 内置皮肤（悬浮立体/云雾拟态/Material）为内存规格不落盘，导入皮肤不得冒用 `builtin.` 保留前缀。

## 构建说明

### 常用命令

```bash
# 编译 debug APK
./gradlew :app:assembleDebug

# 全部单元测试（纯 JVM，无需设备；用例基线见 scripts/unit-test-baseline.txt，只增不减）
(cd ../ziyou-ime-sdk && ./gradlew testDebugUnitTest) && ./gradlew :app:testDebugUnitTest

# 运行单个测试类 / 单个方法
(cd ../ziyou-ime-sdk && ./gradlew testDebugUnitTest --tests "com.ziyou.ime.core.t9.KeyRecordStackTest")
./gradlew :app:testDebugUnitTest --tests "*PinyinHintProviderTest.某方法名"

# Release（R8 混淆 + 签名，需 keystore.properties；产物在 dist/，含 sha256）
./scripts/build-release.sh            # 可选 --rebuild-native / --skip-tests / --abis
```

### Release 构建门禁

[`scripts/build-release.sh`](scripts/build-release.sh) 是正式发布的一键脚本：

- 前置条件：根目录 `keystore.properties`（模板见 `keystore.properties.template`）+ 每个目标 ABI 已有
  `ziyou-ime-sdk/libs/<abi>/librime.a`（或加 `--rebuild-native` 经 SDK 工程内 `librime-prebuilt/build.sh` 重编，
  强制 `WITH_LUA/PREDICT/WITOGRAM=ON` 与 SDK 构建开关一致）；
- 测试门禁：从两工程的 JUnit XML 报告累加实测用例数，低于 `scripts/unit-test-baseline.txt` 基线即失败；
- 产物：`dist/ziyou-ime-v<versionName>-<abis>-release.apk` 及 `.sha256`。

### 可选 Native 模块

JNI 层的 CMake 配置位于 ziyou-ime-sdk 工程（`src/main/jni/librime_jni/CMakeLists.txt`），通过 `option()`
控制可选模块（Lua、Octagram、Predict、Witogram、OpenCC）。启用某模块需两步：

1. 在 `ziyou-ime-sdk/build.gradle.kts` 添加 CMake 参数（如 `-DWITH_PREDICT=ON`）；
2. 经 ziyou-ime-sdk 内 `librime-prebuilt/build.sh` 以对应 `WITH_*=ON` 重编 librime.a
   （插件合并进静态库并直接安装到 `ziyou-ime-sdk/libs/<abi>/`）。

> librime-predict 与 librime-witogram 当前已启用（predict.db 随 assets 分发，witogram 语法模型位于
> `app/src/main/assets/rime/grammar/`）。

### predict.db 自建

联想子库支持完全自建替代官方包（编排指南见 [`docs/skills/engine/predict.db构建指南.md`](docs/skills/engine/predict.db构建指南.md)）：

```bash
python3 scripts/build_predict_db.py   # 种子/外部/采纳语料合并 + 安全过滤 + 双键扩展 + 召回率验收
```

下载更新经 `PredictDbManager` 管理（体积上限 100MB），安装后回盖用户目录并经引擎重部署生效。

## 项目结构

```
ziyou-ime/
├── app/                               # 应用模块（UI + 业务域，消费 ime-sdk）
│   └── src/main/
│       ├── assets/
│       │   ├── rime/                  # Rime 内容（方案 + 词库 + opencc + grammar 语法模型 + lua）
│       │   ├── skills/                # 内置技能（calculator/flowchart/poetry/weather）
│       │   ├── skill_runtime/         # 技能 JS 垫片（imeskill.js）
│       │   └── predict.db             # 联想子库（librime-predict）
│       ├── java/com/ziyou/ime/
│       │   ├── ZiyouApplication.kt    # Application 入口（Rime 引擎预热 + 更新检测调度）
│       │   ├── di/AppContainer.kt     # 组合根：RimeSdk.init 装配部署步骤、引擎/语音/LLM 协调器、上屏监听
│       │   ├── ime/                   # IME 层：Service + 协作类 + 键盘/候选/面板视图
│       │   ├── config/                # SchemaPreference / DisplayModeManager
│       │   ├── core/                  # 业务纯逻辑（level/skill/skin/voice/rag/floating/clipboard 等）
│       │   ├── ai/                    # AI 问答/人设/知识库 RAG + LLM 智能续写（prediction/）
│       │   ├── voice/                 # 流式语音识别（sherpa-onnx 引擎 + 模型下载）
│       │   ├── data/                  # 侧栏符号/剪贴板历史/工具栏配置/联想开关
│       │   ├── dict/                  # 扩展词库：DictManager / DictDownloader / PredictDbManager
│       │   ├── level/                 # 等级持久化：LevelRepository / LevelStats
│       │   ├── skill/ · skin/ · update/ · ui/   # 技能 / 皮肤 / 应用更新 / 设置与 Compose 页面
│       └── res/                       # Android 资源（含 input_method.xml）
├── scripts/                           # 构建/门禁/语料工具（build-release.sh、build_predict_db.py 等）
├── skills-dev/                        # 技能插件开发样例（星座/诗词，含打包产物 .skill）
├── skins-dev/                         # 皮肤开发样例（云朵奶油，pack.sh 打包 .zyskin）
├── docs/                              # 设计文档（可行性方案 + 开发指南）
├── ARCHITECTURE.md                    # 架构设计文档
└── build.gradle.kts / settings.gradle.kts / gradle.properties

../ziyou-ime-sdk/                      # 隔壁独立 SDK 工程（坐标 com.ziyou:ime-sdk，交付 AAR，
│                                      #  主工程以本地文件 AAR 消费（app/libs/ime-sdk-release.aar））
├── librime-prebuilt/                  # librime 源码编译链（build.sh / superbuild / 插件子模块）
├── libs/                              # 产物：<abi>/librime.a + include + LIBRIME_MANIFEST.txt
└── src/{main,test}/
    ├── jni/librime_jni/               # C++/JNI 层（rime_jni.cc / config.cc / CMakeLists.txt）
    └── java/com/ziyou/ime/
        ├── core/                      # RimeNative / RimeApi / Proto / Dispatcher + t9 + prediction 纯逻辑
        ├── daemon/                    # RimeEngine / RimeDeployStep（RimeSession internal）
        ├── config/                    # AssetDeployer / RimeConfigManager
        ├── sdk/                       # RimeSdk 门面 + input/InputSession + state 状态服务
        └── util/T9PinYinUtils.kt      # T9 ↔ 拼音双向映射
```

更完整的架构说明、模块交互与数据流详见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 项目依赖

| 依赖 | 用途 |
|------|------|
| librime（预编译静态库） | Rime 输入引擎（已静态链入 SDK 的 librime_jni.so，含 predict/witogram 插件） |
| `com.ziyou:ime-sdk`（本地文件 AAR：`app/libs/ime-sdk-release.aar`） | 引擎交互 + 通用输入能力：T9 映射 / 九宫格状态机 / 输入管线 / 状态服务 |
| sherpa-onnx 1.13.3（本地 AAR） | 本地流式语音识别引擎 |
| Kotlin Coroutines 1.11 | 异步与单线程 Dispatcher |
| Jetpack Compose (Material3) | 等级/词库/技能/皮肤等设置页面 |
| AndroidX AppCompat / Lifecycle / ViewModel / Preference | 设置页与偏好存储 |
| AndroidX WebKit | 技能面板 WebView 沙箱能力 |

需要 `INTERNET` 权限（扩展词库/语音模型下载、LLM 续写、AI 问答、应用更新）。

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
- 按「能力 vs 业务」分流：输入法通用能力（引擎交互、T9、候选/编码语义）归 ziyou-ime-sdk，业务纯逻辑（计分、技能校验等）置于 `:app`，禁止反向依赖
- C++ 代码遵循项目 `.clang-format` 配置（C++17 标准）
- 所有 Rime 调用必须通过 `RimeDispatcher` 调度（librime 非线程安全）
- 输入热路径零磁盘 IO，只允许 O(1) 内存操作
- JNI 层使用 RAII 管理资源，避免内存泄漏
- 提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/)

### 运行检查

```bash
# 编译检查
./gradlew :app:assembleDebug

# 单元测试（SDK + app；SDK 测试需在其工程目录内触发）
(cd ../ziyou-ime-sdk && ./gradlew testDebugUnitTest) && ./gradlew :app:testDebugUnitTest

# 代码格式化（JNI 层，源码位于 SDK 工程）
clang-format -i ../ziyou-ime-sdk/src/main/jni/librime_jni/*.cc ../ziyou-ime-sdk/src/main/jni/librime_jni/*.h
```

## 许可证

本项目以 GPL-3.0-or-later 许可证开源（librime 同为 GPL-3.0）。

```
SPDX-License-Identifier: GPL-3.0-or-later
```
