---
kind: logging_system
name: Android 原生 Log 直接输出（无统一日志框架）
category: logging_system
scope:
    - '**'
source_files:
    - app/src/main/java/com/ziyou/ime/ZiyouApplication.kt
    - app/src/main/java/com/ziyou/ime/ai/AiChatClient.kt
    - app/src/main/java/com/ziyou/ime/config/AssetDeployer.kt
    - app/build.gradle.kts
---

该仓库未引入任何第三方日志框架或统一的日志抽象层，所有 Kotlin 代码直接使用 Android 平台自带的 `android.util.Log` 进行日志输出。具体表现如下：

1. **使用方式**：各模块通过 `import android.util.Log` 引入后，以 `Log.i/d/w/e/v(TAG, message)` 形式直接调用，TAG 通常定义为类级 `private const val TAG = "..."`。
2. **分布范围**：日志调用散落在 `app/src/main/java/com/ziyou/ime/` 下的各个业务类中（如 `ZiyouApplication.kt`、`AiChatClient.kt`、`AssetDeployer.kt`、`KnowledgeRepository.kt` 等），`core-logic` 纯逻辑模块未见日志调用。
3. **日志级别**：主要使用 `Log.i`（信息）、`Log.w`（警告）、`Log.e`（错误），少量使用 `Log.d`（调试）。
4. **结构化字段**：未采用结构化日志格式，消息为拼接字符串，包含错误码、文件路径、版本信息等上下文。
5. **构建配置**：`build.gradle.kts` 中无任何日志相关依赖声明，仅依赖 AndroidX 和 Compose 等基础库。
6. **初始化**：`ZiyouApplication` 的 `onCreate()` 中仅记录一条初始化日志，无全局日志配置或过滤器设置。

由于缺乏统一的日志抽象、集中配置和输出路由机制，日志输出完全依赖 Android 系统的 logcat，无法在运行时动态调整级别、过滤或重定向到文件/远程服务。