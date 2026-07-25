---
kind: external_dependency
name: Rime 输入法引擎核心库
slug: rime-librime
category: external_dependency
category_hints:
    - vendor_identity
    - sdk_real_api
scope:
    - '**'
source_files:
    - app/src/main/jni/librime_jni/CMakeLists.txt
    - librime-prebuilt/README.md
---

### Rime 输入法引擎（librime）
- **角色**：字由输入法的核心输入引擎，提供拼音、仓颉等输入方案的底层实现
- **集成方式**：通过 JNI 层（C++17）与 Android 应用交互，使用预编译静态库 `librime.a`（arm64-v8a ABI）
- **线程模型**：librime 非线程安全，所有调用必须经 `RimeDispatcher`（单线程 Executor + 协程 withContext）顺序执行
- **资源管理**：JNI 层使用 RAII（SessionHolder/CString/JRef）确保资源不泄漏
- **词库支持**：通过 `.dict.yaml` 和 `.schema.yaml` 配置文件管理词库和输入方案
- **验证**：需参考官方 librime 文档确认具体 API 方法名和参数