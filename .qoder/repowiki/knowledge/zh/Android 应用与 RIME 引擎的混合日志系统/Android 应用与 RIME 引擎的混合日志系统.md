---
kind: logging_system
name: Android 应用与 RIME 引擎的混合日志系统
category: logging_system
scope:
    - '**'
source_files:
    - app/src/main/java/com/ziyou/ime/ZiyouApplication.kt
    - app/src/main/java/com/ziyou/ime/config/AssetDeployer.kt
    - app/src/main/java/com/ziyou/ime/config/RimeConfigManager.kt
    - app/src/main/java/com/ziyou/ime/core/RimeDispatcher.kt
    - app/src/main/jni/librime_jni/rime_jni.cc
    - librime-prebuilt/librime/src/rime/common.h
    - librime-prebuilt/librime/src/rime/no_logging.h
---

本仓库采用**分层混合日志方案**：Android/Kotlin 层使用 Android 原生 `android.util.Log`，JNI/C++ 层通过 `__android_log_print` 直接输出到 logcat，而底层 librime C++ 引擎默认使用 Google glog（`LOG(INFO/WARNING/ERROR)`），并通过头文件开关可完全禁用。三层日志最终统一汇聚到 Android logcat。

### 1. Android/Kotlin 层日志
- 所有 Kotlin 模块均直接 `import android.util.Log`，以类内 `companion object { private const val TAG = "..." }` 定义标签，调用 `Log.i/d/w/e` 等 API。
- 典型使用位置：`ZiyouApplication.kt`、`config/AssetDeployer.kt`、`config/RimeConfigManager.kt`、`core/RimeDispatcher.kt`、`daemon/RimeSession.kt`、`ui/*`、`ime/*` 等，覆盖应用初始化、资源部署、配置管理、输入法逻辑、UI 交互等全链路。
- 未发现统一的 LogWrapper 或日志门面，各模块自行决定日志级别（INFO/WARN/ERROR/DEBUG）和 TAG 命名。

### 2. JNI/C++ 桥接层日志
- `app/src/main/jni/librime_jni/rime_jni.cc` 中直接使用 `__android_log_print(ANDROID_LOG_INFO|WARN, "RimeJNI", ...)` 输出关键生命周期日志（如引擎启动、重复初始化警告）。
- 未引入第三方 C++ 日志库，保持最小依赖。

### 3. RIME 引擎层日志（glog）
- `librime-prebuilt/librime/src/rime/common.h` 包含 `<glog/logging.h>`，引擎内部广泛使用 `LOG(INFO/WARNING/ERROR)` 和 `DLOG(INFO)`（调试宏）。
- **关键约束**：`rime_jni.cc` 在设置 `RimeTraits` 时将 `traits.log_dir = ""`（注释明确“设为空以仅输出到logcat”），从而禁用 glog 的文件输出，仅保留 logcat 输出。
- `librime/src/rime/no_logging.h` 提供完整的 `RIME_NO_LOG` 宏集，可将 `LOG/DLOG/CHECK/DCHECK` 等全部替换为空操作，用于构建无日志版本。

### 4. 架构与约定
- **分层隔离**：Kotlin → JNI (`__android_log_print`) → glog（被禁写到文件），形成清晰的跨语言边界。
- **统一出口**：生产环境仅输出到 logcat，不产生额外磁盘文件，符合 Android 平台最佳实践。
- **可裁剪性**：通过 `no_logging.h` 可在编译期完全移除 glog 开销，适用于对体积敏感的场景。
- **无结构化字段**：当前日志均为纯文本拼接，未使用 JSON 等结构化格式；TAG 由各模块自行定义，缺乏全局规范。

### 5. 约束与规则
- glog 文件输出被显式禁用（`log_dir = ""`），这是构建时的硬性约束。
- 若启用 `no_logging.h`，所有 `LOG/DLOG/CHECK` 宏将被替换为空，属于编译期强制行为。
- Kotlin 层未建立统一的日志封装或级别控制策略，属于松散约定而非强制规则。