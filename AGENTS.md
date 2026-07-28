# AGENTS.md — 字由输入法 (Ziyou IME)

Android 输入法应用，基于 Rime 引擎（Kotlin + C/C++ JNI + librime），支持 QWERTY 与九宫格 T9 输入。

## 禁止修改区域

以下目录包含第三方预编译产物或外部资产，**禁止**修改：

- `libs/include/` — librime C 头文件（预编译库接口）
- `libs/arm64-v8a/` — 预编译静态库 `librime.a`
- `librime-prebuilt/` — librime 源码、Boost 依赖、superbuild 构建脚本
- `librime-prebuilt/boost/` — Boost 库源码（1.89.0）
- `librime-prebuilt/librime/` — librime 上游源码
- `gradle/wrapper/` — Gradle Wrapper 分发包

如需更新引擎或依赖，请通过专用构建流程（见 Memory 中的 librime 预编译库构建规范）。

## 测试命令

```bash
# 全量单元测试（JVM，无需 Android 设备）
./gradlew :core-logic:testDebugUnitTest :app:testDebugUnitTest

# 仅 core-logic 纯逻辑测试
./gradlew :core-logic:testDebugUnitTest

# 仅 app 模块测试
./gradlew :app:testDebugUnitTest
```

**总用例**：38 个（`:core-logic` 25 + `:app` 13）。

测试覆盖：T9 拼音双向映射、九宫格状态机、等级计分、拼音提示、核心输入路径（processKey / processKeyBulk / 退格 / 回车 / 面板路由 / 异常）。
引擎依赖逻辑可通过 `AppContainer.overrideRimeEngine()` 注入 fake 实现测试。

## 模块依赖方向

```
:app  ──依赖──▶  :core-logic
（单向，由编译器强制）
```

- `:core-logic` — 纯 Kotlin 逻辑库，**无** Android UI / JNI 依赖。可独立 JVM 单测。
- `:app` — Android 应用 + JNI + UI + 业务域持久化。依赖 `:core-logic`。

`:app` 内部五层栈（单向向下）：`UI → IME → Core → JNI → Engine`

**规则**：
- `:core-logic` 中**禁止** import `android.*`、`com.ziyou.ime.ime.*`、`com.ziyou.ime.ui.*`
- `:app` 中**禁止** `:core-logic` 反向引用 `:app` 的类
- 无 Android/JNI 依赖的纯逻辑应下沉到 `:core-logic`

## 变更检查清单

修改代码前，按此清单自检：

- [ ] **依赖方向**：变更是否保持了 `:app → :core-logic` 单向？
- [ ] **禁止区域**：是否触及了上述禁止修改目录？
- [ ] **测试覆盖**：变更涉及的逻辑是否有对应单元测试？新逻辑是否需要同步添加测试？
- [ ] **线程安全**：Rime API 调用是否通过 `RimeDispatcher.dispatch`？是否避免了主线程阻塞？
- [ ] **热路径 IO**：输入热路径（`onCommit`）是否保持 O(1) 内存操作、无磁盘 IO？
- [ ] **资源管理**：JNI 层是否使用 RAII 管理 Session / CString / JRef？
- [ ] **引擎接口**：是否通过 `RimeEngine` / `RimeApi` 接口而非直接调用 `RimeSession` 单例？

## 项目结构速查

| 路径 | 说明 |
|------|------|
| `app/src/main/java/com/ziyou/ime/ime/` | IME 层：输入逻辑、键盘管理、拼音提示、悬浮形态 |
| `app/src/main/java/com/ziyou/ime/ui/` | UI 层：设置页、等级页、词库管理（Jetpack Compose） |
| `app/src/main/java/com/ziyou/ime/core/` | Core 层：RimeApi 接口、RimeDispatcher、DI 组合根 |
| `app/src/main/java/com/ziyou/ime/daemon/` | 引擎层：RimeEngine 接口、RimeSession 生命周期 |
| `app/src/main/java/com/ziyou/ime/level/` | 等级体系业务域 |
| `app/src/main/java/com/ziyou/ime/dict/` | 扩展词库业务域 |
| `app/src/main/java/com/ziyou/ime/skill/` | 技能插件业务域 |
| `app/src/main/jni/librime_jni/` | JNI 层：rime_jni.cc、config.cc |
| `core-logic/src/main/java/com/ziyou/ime/core/` | 纯逻辑：T9、等级计分、技能校验、悬浮几何 |
| `core-logic/src/main/java/com/ziyou/ime/util/` | 工具类：T9PinYinUtils |
| `app/src/test/` + `core-logic/src/test/` | 单元测试 |
| `app/src/main/assets/rime/` | Rime 方案配置（.schema.yaml / .dict.yaml） |
| `docs/` | 设计文档与可行性方案 |

## 架构文档

完整架构设计、数据流、线程模型、设计决策与 API 参考见 [ARCHITECTURE.md](ARCHITECTURE.md)。
