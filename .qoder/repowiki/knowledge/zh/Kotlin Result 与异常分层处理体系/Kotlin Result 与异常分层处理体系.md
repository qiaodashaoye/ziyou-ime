---
kind: error_handling
name: Kotlin Result 与异常分层处理体系
category: error_handling
scope:
    - '**'
source_files:
    - app/src/main/java/com/ziyou/ime/ai/AiChatClient.kt
    - app/src/main/java/com/ziyou/ime/ai/knowledge/KnowledgeImporter.kt
    - app/src/main/java/com/ziyou/ime/dict/DictDownloader.kt
    - app/src/main/java/com/ziyou/ime/skill/SkillRuntime.kt
    - app/src/main/java/com/ziyou/ime/skill/SkillBridge.kt
---

该工程在 Kotlin/Android 环境下采用「Result 类型 + 受控异常」的分层错误处理策略，核心思路是：对外 API 返回 kotlin.Result<T> 以显式表达成功/失败分支，内部实现使用 IOException、IllegalStateException、自定义 SkillApiException 等异常进行快速失败（fail-fast），并通过 runCatching / try-catch 将异常捕获并包装为 Result.failure。

1. 系统与方法
- 标准库 Result<T> 作为跨模块的错误传播载体，配合 .fold()、.onSuccess、.onFailure 进行消费；
- runCatching { ... } 包裹可能抛异常的代码块，自动转为 Result；
- 协程场景下通过 withContext(Dispatchers.IO) 执行 IO，并在外层用 runCatching 捕获；
- 自定义异常 SkillApiException(message: String) : Exception 用于技能脚本 API 层的业务错误，便于 Bridge 层区分“预期内业务错误”和“未预期异常”。

2. 关键文件与位置
- app/src/main/java/com/ziyou/ime/ai/AiChatClient.kt：网络请求统一返回 Result<String>，HTTP 错误码映射为用户可读消息，IO 异常被捕获后包装为 Result.failure(IOException(...))。
- app/src/main/java/com/ziyou/ime/ai/knowledge/KnowledgeImporter.kt：知识库导入流水线全部 suspend + runCatching，校验失败直接 throw IOException(...)，由上层 Result 承载。
- app/src/main/java/com/ziyou/ime/dict/DictDownloader.kt：下载器对 catalog JSON 解析使用 runCatching 静默丢弃非法条目，URL 白名单校验失败抛出 IOException。
- app/src/main/java/com/ziyou/ime/skill/SkillRuntime.kt：技能运行时所有方法通过 complete: (Result<String?>) -> Unit 回调结果，同步路径用 runCatching { handleSync(...) }，异步 storage/image 路径同样以 Result 回传。
- app/src/main/java/com/ziyou/ime/skill/SkillBridge.kt：Bridge 层统一 result.fold(onSuccess/onFailure)，对 SkillApiException 透传 message，其他异常降级为通用“内部错误”。

3. 架构与约定
- 分层边界：UI/调用方只看到 Result<T>，不感知具体异常类型；内部实现自由抛 IOException/IllegalStateException/SkillApiException，由边界处统一捕获。
- 快速失败：参数校验、权限检查、状态检查等前置条件不满足时立即 throw，避免继续执行无效逻辑。
- 用户可读错误：所有抛出的异常消息均包含面向用户的中文描述（如“仅允许 HTTPS 的 AI 服务地址”“知识库容量已满”），而非原始堆栈。
- 安全兜底：Bridge 层对未预期异常做降级处理，确保脚本侧不会崩溃，始终收到 ok=false + 通用错误信息。

4. 约束与规则
- 所有对外暴露的 suspend 函数（如 importFile、ask、syncFolder）必须返回 Result<T>，不得吞掉异常或返回 null。
- 网络相关 I/O 必须限制超时与响应大小上限，超限直接 throw IOException("...")。
- 技能脚本 API 错误统一使用 SkillApiException，以便 Bridge 层区分业务错误与系统异常。
- 第三方不可信输入（如远程 catalog、WebView 注入消息）必须通过 runCatching 隔离，失败时记录日志并跳过/降级，不中断整体流程。