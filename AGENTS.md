# AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

字由输入法（Ziyou IME）：基于 librime 的 Android 中文输入法。Kotlin + 协程，JNI 层 C++17，键盘 UI 纯 Canvas 绘制，设置类页面 Jetpack Compose (Material3)。详细架构见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 常用命令

```bash
# 编译 debug APK
./gradlew :app:assembleDebug

# 全部单元测试（纯 JVM，无需设备；用例数基线见 scripts/unit-test-baseline.txt，只增不减）
./gradlew :core-logic:testDebugUnitTest :app:testDebugUnitTest

# 运行单个测试类 / 单个方法
./gradlew :core-logic:testDebugUnitTest --tests "com.ziyou.ime.core.t9.KeyRecordStackTest"
./gradlew :app:testDebugUnitTest --tests "*PinyinHintProviderTest.某方法名"

# Release（R8 混淆 + 签名，需 keystore.properties；正式发布用脚本）
./scripts/build-release.sh            # 产物在 dist/，可加 --rebuild-native / --skip-tests

# JNI 层格式化
clang-format -i app/src/main/jni/librime_jni/*.cc app/src/main/jni/librime_jni/*.h

# 重建 librime 预编译静态库（产物安装到 libs/<abi>/librime.a，日常开发不需要）
cd librime-prebuilt && make librime   # 可选插件: make lua / octagram / predict / witogram
```

构建环境要点：

- Gradle 9.5 + AGP 9.x，daemon 需 **JDK 21**（`gradle.properties` 已固定 `org.gradle.java.installations.paths` 指向 Android Studio JBR，并关闭 auto-detect；不要改回自动探测，IDE 同捆 JRE 缺 jlink 会导致构建失败）。
- NDK r26+ / CMake 3.22.1；当前仅打包 `arm64-v8a`（`app/build.gradle.kts` 的 `abiFilters`）。
- `:app` 的 JNI 链接依赖 `libs/arm64-v8a/librime.a` + `libs/include/rime_api.h`（由 `librime-prebuilt/` 生成，通常已就位，勿删）。
- 语音识别依赖 `app/libs/sherpa-onnx-1.13.3.aar`（不入 git；缺失时跑 `scripts/fetch-sherpa-onnx.sh` 下载，默认走 GitHub Release，备选 hf-mirror 镜像）。

## 架构大图

Gradle 双模块，依赖方向由编译器强制单向：**`:app` → `:core-logic`**。

- **`:core-logic`**：纯 Kotlin 逻辑库，禁止任何 Android UI / JNI 依赖。承载 T9 双向映射（`util/T9PinYinUtils`）、九宫格状态机（`core/t9/KeyRecordStack`）、等级计分（`core/level/LevelEngine`）、技能包校验（`core/skill/*`）、悬浮几何（`core/floating`）。**新增可复用纯逻辑一律下沉到这里并配套单测，禁止反向依赖 `:app`。**
- **`:app`**：五层引擎栈（单向向下）：
  - **UI 层**：`ui/` — SettingsActivity（传统 View）、Level/DictManager/SkillManager 等 Compose 页面
  - **IME 层**：`ime/` — `ZiYouInputMethodService`（只管生命周期 + 视图装配）拆分出五个协作类：`InputLogicController`（输入热路径/上屏路由）、`KeyboardLayoutManager`（键盘视图组装）、`PinyinHintProvider`（九宫格拼音提示纯逻辑）、`DisplayModeController`（停靠/悬浮形态）、`SkillPanelCoordinator`（技能面板三态布局）。键盘视图继承 `BaseKeyboardView`（Canvas 绘制）。
  - **Core 层 + DI**：`core/` + `daemon/` + `di/AppContainer`（组合根）。`RimeEngine`（生命周期接口，实现 `RimeSession` object 单例）+ `RimeApi`（操作接口，实现 `SimpleRimeImpl`），测试用 `AppContainer.overrideRimeEngine()` 注入替身。
  - **JNI 层**：`app/src/main/jni/librime_jni/`（rime_jni.cc / config.cc 等，RAII 管理资源）
  - **Engine 层**：预编译 librime 静态库
- **横向业务域**（:app 内）：`level/` 等级体系、`dict/` 扩展词库、`skill/` 技能插件（WebView 沙箱）、`data/` 侧栏符号与联想开关。

## 硬性约束（改代码前必读）

1. **librime 非线程安全**：所有 Rime 调用必须经 `RimeDispatcher`（单线程协程调度器）；调用方只用 `suspend` 函数。`InputLogicController` 另用 `inputMutex` 把每次按键的多步调用串行化 —— 不要绕过这两层。
2. **输入热路径零磁盘 IO**：`onCommit` 等热路径只允许 O(1) 内存操作（参考 `LevelStats`：原子自增 + 阈值/生命周期节点异步落盘）。热路径优先用批量 JNI（`processKeyBulk` 一次跨界），新增引擎交互避免增加 JNI 往返。
3. **添加 JNI 函数三步**：`rime_jni.cc` 导出 `Java_com_ziyou_ime_core_RimeNative_xxx` → `RimeNative.kt` 声明 `external fun` → `RimeApi.kt` 加接口方法并在 `SimpleRimeImpl` 经 `dispatcher.dispatch` 实现。JNI 层用 RAII（`SessionHolder`/`CString`/`JRef`/`JString`）。
4. **KeyRecordStack 不变式**：列表顺序 == Rime 编码串逻辑顺序（选拼音原地替换而非追加），改九宫格逻辑必须维持该不变式并跑通对应单测。
5. **依赖装配只在组合根**：daemon 层不得直接 import config/dict 业务模块（经 `RimeSession.deploySteps` 反转）；输入热路径不硬编码业务单例（经 `commitListeners` 注入）。
6. **隐私红线**：等级统计只记录脱敏聚合计数，绝不记录输入内容；技能插件不得读取用户在其他应用的输入。
7. 提交信息遵循 Conventional Commits；Kotlin 遵循官方编码规范。

## 其他入口

- Rime 配置/方案：`app/src/main/assets/rime/`（方案清单在 `default.yaml` 的 `schema_list`；新增方案后需 fullCheck 重部署）。
- 可选 Native 模块（Lua/Octagram/Predict/Witogram/OpenCC）：`app/build.gradle.kts` 加 CMake 参数（如 `-DWITH_PREDICT=ON`）+ 对应静态库放入 `libs/<abi>/`；librime-predict 与 librime-witogram 当前已启用。
- 皮肤开发样例：`skins-dev/`（`pack.sh` 打包 `.zyskin`）；技能插件样例：`skills-dev/`（打包 `.skill`，开发指南见 `docs/技能插件开发指南.md`）。
- 设计文档集中在 `docs/`；仓库内 `.qoder/agents/` 提供本项目专用 subagent（开发/评审/测试/性能审计等），复杂任务可委派。
