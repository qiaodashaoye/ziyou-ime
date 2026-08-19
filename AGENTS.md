# AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

字由输入法（Ziyou IME）：基于 librime 的 Android 中文输入法。Kotlin + 协程，JNI 层 C++17，键盘 UI 纯 Canvas 绘制，设置类页面 Jetpack Compose (Material3)。详细架构见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 常用命令

```bash
# 编译 debug APK
./gradlew :app:assembleDebug

# 全部单元测试（纯 JVM，无需设备；用例数基线见 scripts/unit-test-baseline.txt，只增不减；
# composite build 下 SDK（隔壁工程 ziyou-ime-sdk）的测试需在其目录内触发）
(cd ../ziyou-ime-sdk && ./gradlew testDebugUnitTest) && ./gradlew :app:testDebugUnitTest

# 运行单个测试类 / 单个方法
(cd ../ziyou-ime-sdk && ./gradlew testDebugUnitTest --tests "com.ziyou.ime.core.t9.KeyRecordStackTest")
./gradlew :app:testDebugUnitTest --tests "*PinyinHintProviderTest.某方法名"

# Release（R8 混淆 + 签名，需 keystore.properties；正式发布用脚本）
./scripts/build-release.sh            # 产物在 dist/，可加 --rebuild-native / --skip-tests

# JNI 层格式化（源码位于隔壁 SDK 工程）
clang-format -i ../ziyou-ime-sdk/src/main/jni/librime_jni/*.cc ../ziyou-ime-sdk/src/main/jni/librime_jni/*.h

# 重建 librime 预编译静态库（工具链已迁至 ziyou-ime-sdk，产物安装到其 libs/<abi>/librime.a，日常开发不需要）
cd ../ziyou-ime-sdk/librime-prebuilt && make librime   # 可选插件: make lua / octagram / predict / witogram
```

构建环境要点：

- Gradle 9.5 + AGP 9.x，daemon 需 **JDK 21**（`gradle.properties` 已固定 `org.gradle.java.installations.paths` 指向 Android Studio JBR，并关闭 auto-detect；不要改回自动探测，IDE 同捆 JRE 缺 jlink 会导致构建失败）。
- NDK r26+ / CMake 3.22.1；当前仅打包 `arm64-v8a`（`app/build.gradle.kts` 的 `abiFilters`）。
- JNI 链接发生在隔壁工程 ziyou-ime-sdk（librime.a 静态链入 librime_jni.so，app 经 composite build 间接获得），其依赖的 `ziyou-ime-sdk/libs/arm64-v8a/librime.a` + `libs/include/rime_api.h` 由该工程内 `librime-prebuilt/` 生成并直接安装，通常已就位，勿删。
- 语音识别依赖 `app/libs/sherpa-onnx-1.13.3.aar`（不入 git；缺失时跑 `scripts/fetch-sherpa-onnx.sh` 下载，默认走 GitHub Release，备选 hf-mirror 镜像）。

## 架构大图

主工程仅 `:app` 单模块，经坐标 `com.ziyou:ime-sdk` 依赖隔壁独立 SDK 工程 `ziyou-ime-sdk`（settings.gradle.kts `includeBuild` composite 自动替换为源码；对外交付 AAR）。依赖方向由编译器强制单向：**`:app` → ime-sdk**。

- **`ziyou-ime-sdk`（隔壁工程）**：引擎交互 + 通用输入能力。承载五层引擎栈的 Core/JNI/Engine 层：`RimeSdk` 门面、`RimeEngine`/`RimeApi`（实现 `RimeSession`/`SimpleRimeImpl` 均已 internal）、`InputSession` 输入管线（Mutex 事务串行）、`PreeditController`/`CandidatesService`/`SchemaService` 状态服务、T9 状态机（`KeyRecordStack`/`T9PinYinUtils`）、JNI 层（`src/main/jni/librime_jni/`，RAII）与 librime-prebuilt 编译链。**引擎交互类新代码一律去这里。**
- **`:app`**：UI + 业务域：
  - **UI 层**：`ui/` — SettingsActivity（传统 View）、Level/DictManager/SkillManager 等 Compose 页面
  - **IME 层**：`ime/` — `ZiYouInputMethodService`（生命周期 + 视图装配）+ 协作类：`InputLogicController`（业务薄层：CommitTarget 路由/上屏监听/图片上屏）、`KeyboardLayoutManager`、`PinyinHintProvider`（九宫格拼音提示，内容层约定）、`DisplayModeController`、`SkillPanelCoordinator`。键盘视图继承 `BaseKeyboardView`（Canvas 绘制）。
  - **装配根**：`di/AppContainer` — 经 `RimeSdk.init` 装配部署步骤、暴露 `RimeEngine`，测试用 `AppContainer.overrideRimeEngine()` 注入替身。
  - **业务纯逻辑**：`core/`（原 :core-logic 分流：level/skill/skin/voice/rag/floating 等）+ `util/AppVersionUtils`。
- **横向业务域**（:app 内）：`level/` 等级体系、`dict/` 扩展词库、`skill/` 技能插件（WebView 沙箱）、`data/` 侧栏符号与联想开关。

## 硬性约束（改代码前必读）

1. **librime 非线程安全**：所有 Rime 调用必须经 `RimeDispatcher`（单线程协程调度器）；调用方只用 `suspend` 函数。`InputLogicController` 另用 `inputMutex` 把每次按键的多步调用串行化 —— 不要绕过这两层。
2. **输入热路径零磁盘 IO**：`onCommit` 等热路径只允许 O(1) 内存操作（参考 `LevelStats`：原子自增 + 阈值/生命周期节点异步落盘）。热路径优先用批量 JNI（`processKeyBulk` 一次跨界），新增引擎交互避免增加 JNI 往返。
3. **添加 JNI 函数三步**（均在隔壁 SDK 工程）：`rime_jni.cc` 导出 `Java_com_ziyou_ime_core_RimeNative_xxx` → `RimeNative.kt` 声明 `external fun` → `RimeApi.kt` 加接口方法并在 `SimpleRimeImpl` 经 `dispatcher.dispatch` 实现。JNI 层用 RAII（`SessionHolder`/`CString`/`JRef`/`JString`）。
4. **KeyRecordStack 不变式**：列表顺序 == Rime 编码串逻辑顺序（选拼音原地替换而非追加），改九宫格逻辑必须维持该不变式并跑通对应单测。
5. **依赖装配只在组合根**：SDK 内部不得直接 import 宿主业务模块（部署步骤经 `RimeSdk.init(RimeSdkConfig.preDeploySteps)` 反转）；输入热路径不硬编码业务单例（经 `commitListeners` 注入）。
6. **隐私红线**：等级统计只记录脱敏聚合计数，绝不记录输入内容；技能插件不得读取用户在其他应用的输入。
7. 提交信息遵循 Conventional Commits；Kotlin 遵循官方编码规范。

## 其他入口

- Rime 配置/方案：`app/src/main/assets/rime/`（方案清单在 `default.yaml` 的 `schema_list`；新增方案后需 fullCheck 重部署）。
- 可选 Native 模块（Lua/Octagram/Predict/Witogram/OpenCC）：`ziyou-ime-sdk/build.gradle.kts` 加 CMake 参数（如 `-DWITH_PREDICT=ON`）+ 经 ziyou-ime-sdk 内 `librime-prebuilt/build.sh` 以对应 `WITH_*=ON` 重编 librime.a（产物直接安装到 `ziyou-ime-sdk/libs/<abi>/`）；librime-predict 与 librime-witogram 当前已启用。
- 皮肤开发样例：`skins-dev/`（`pack.sh` 打包 `.zyskin`）；技能插件样例：`skills-dev/`（打包 `.skill`，开发指南见 `docs/技能插件开发指南.md`）。
- 设计文档集中在 `docs/`；仓库内 `.qoder/agents/` 提供本项目专用 subagent（开发/评审/测试/性能审计等），复杂任务可委派。
