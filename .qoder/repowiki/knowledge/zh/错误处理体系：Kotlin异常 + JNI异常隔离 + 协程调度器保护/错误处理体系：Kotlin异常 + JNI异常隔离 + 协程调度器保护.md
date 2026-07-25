---
kind: error_handling
name: 错误处理体系：Kotlin异常 + JNI异常隔离 + 协程调度器保护
category: error_handling
scope:
    - '**'
source_files:
    - app/src/main/java/com/ziyou/ime/core/RimeDispatcher.kt
    - app/src/main/java/com/ziyou/ime/core/RimeNative.kt
    - app/src/main/java/com/ziyou/ime/core/RimeMessage.kt
    - app/src/main/java/com/ziyou/ime/config/AssetDeployer.kt
    - app/src/main/java/com/ziyou/ime/config/RimeConfigManager.kt
    - app/src/main/java/com/ziyou/ime/core/SimpleRimeImpl.kt
    - app/src/main/jni/librime_jni/rime_jni.cc
---

本工程的错误处理采用分层策略：Kotlin层使用标准异常与日志记录，JNI层通过try-catch隔离C++异常避免崩溃，核心引擎调用通过RimeDispatcher单线程调度器提供超时与关闭保护。整体未引入第三方错误类型或Result封装，而是以传统异常+日志为主。

**1. Kotlin层异常处理模式**
- 配置与资源操作（AssetDeployer、RimeConfigManager）统一使用 try-catch 捕获 IOException、PackageManager.NameNotFoundException 等具体异常，失败时记录 Log.e 并返回布尔值或 null，不向上抛出。
- RimeDispatcher 在 dispatch() 中捕获所有 Exception，记录日志后重新抛出，确保上层能感知异常；dispatchWithTimeout() 将 TimeoutCancellationException 转换为 null 返回，体现“超时即成功”的容错语义。
- SimpleRimeImpl 作为 RimeApi 实现，所有方法通过 dispatcher.dispatch{} 包裹，异常由调度器统一处理，调用方无需关心底层线程安全。

**2. JNI层异常隔离**
- RimeNative.kt 在 init 块中捕获 UnsatisfiedLinkError，设置 isLoaded=false 并记录日志，避免 .so 加载失败导致应用启动崩溃。
- rime_jni.cc 中所有 C++ 异常均被 catch(...) 捕获，例如 SessionHolder 构造失败时返回空 session_id，JNI_OnLoad 中的 notificationHandler 在回调 Java 后检查并清除 JNI 异常（env->ExceptionCheck/ExceptionClear），防止异常从 JNI 层泄漏到 Android 进程。

**3. 核心调度与状态保护**
- RimeDispatcher 使用 AtomicBoolean 标记 isShutdown，关闭后 dispatch() 直接抛出 IllegalStateException("RimeDispatcher 已关闭")，dispatchWithTimeout() 返回 null，确保资源释放后不再接受新任务。
- 所有 librime API 调用强制通过该调度器的单线程执行器，避免非线程安全的原生库引发竞态条件导致的不可预测错误。

**4. 消息驱动的错误通知**
- RimeMessage 使用 sealed class 定义 SchemaMessage、OptionMessage、DeployMessage、UnknownMessage 四种消息类型，由 JNI 层通过 handleRimeMessage() 转换后经 RimeMessageHandler 的 SharedFlow 广播给 UI 层订阅者。
- UnknownMessage 用于处理未知消息类型，保证消息处理的完备性。

**5. 约定与约束**
- 所有对 RimeNative 的调用必须经过 RimeDispatcher.dispatch{} 包裹，这是工程内唯一允许的调用路径。
- 配置读取类方法（如 getSchemaInt、getConfigString）在打开 peer 失败时返回 null 而非抛出异常，由调用方判断空值。
- 资源部署失败时返回 false 并记录详细日志，由调用方决定是否降级或重试。
- C++ 层禁止抛出未捕获异常，所有异常必须在 JNI 边界内消化或转换为返回值。