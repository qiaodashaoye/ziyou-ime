# librime-witogram 集成可行性分析报告

- 分支：`feat/librime-witogram-integration`
- 关联：《白霜拼音词库迁移可行性方案》三期增量（语法模型）
- 上游：https://github.com/levelel/librime-witogram （pin `c6fe4525`，git submodule 纳管）

## 1. 结论摘要

**建议集成，且以 witogram 原位替代原三期方案中的 octagram。** 本报告随附的实施已完成：
构建接入（`WITH_WITOGRAM`）、schema grammar 段恢复、`zh-moqi.gram` 随 assets 部署、
防回归单测。PoC 编译结果见 §7。

## 2. 插件核心功能与技术实现

librime-witogram 是几维输入法（Outwit）开源的 Rime N-gram 语法模型插件，
定位为官方 librime-octagram（八股文）的演进替代方案。

### 2.1 核心能力

| 能力 | 说明 |
|---|---|
| 语法模型评分 | 注册 `grammar` 组件（与 octagram 同名同位），为整句/多候选排序提供 n-gram 信号 |
| .gram 直读 | 完整移植 octagram `GramDb` + `gram_encoding`，评分逻辑与原版逐位一致 |
| .klm（KenLM） | mmap + 量化的标准 KenLM 格式，可用 `lmplz` 标准工具链重训 |
| 搭配检测 | 支持 `collocation_mode` / `collocation_penalty` / `non_collocation_penalty` / `rear_penalty` 全套 octagram 配置项 |

### 2.2 技术实现要点

- **架构重构**：修复 octagram 变长编码状态指针越界隐患；补全 backoff 权重，OOV 符合统计分布。
- **内存模型**：mmap 零拷贝冷启动，RSS 由全量加载降为按活跃词命中率分页 —— 移动端关键优势。
- **容错**：`grammar/language` 未配置则整个组件不激活；模型文件缺失时
  `GramDb::Load()` 返回 false 并降级（日志提示），不会崩溃。
- **官方实测**（72 句测试集 Top-1）：

| 管线 | Top-1 | 相对 octagram |
|---|---|---|
| octagram 原版（.gram） | 68.33% | 基线 |
| witogram GramDb 直读（.gram） | 68.00% | ≈追平（无损替代） |
| witogram KenLM collocation（.klm） | 63.67% | -4.33% |
| witogram KenLM backoff（.klm） | 61.67% | -6.66% |

结论：**ziyou 走 .gram 直读路径**（zh-moqi.gram），行为与 octagram 等价；
.klm 路径为未来挂载万象 LTS 等大模型保留升级通道。

### 2.3 Rime 生态应用场景

- 整句拼音输入的搭配纠错（白霜/万象拼音桌面端的核心智能来源）；
- 与 predict 插件、Lua 联动做混合联想（几维路线图）；
- 生态现状：目前主要随几维（Windows）落地，**Android 前端无公开编译先例**，
  本项目为移动端首批集成实践。

## 3. 与 ziyou-ime 的契合度

1. **三期空位原位替代**：迁移方案三期 = "octagram 重编 + 语言模型分发 ≈ 4 人日"，
   witogram 直接顶替 octagram，配置段完全同构。
2. **上游行为强依赖 grammar 信号**：白霜上游 schema 明确注释 ——
   `zh-moqi` 模型"很小几乎不起作用但不能不配置，为了让 non_collocation_penalty 生效"；
   默认 -12 惩罚对白霜词库过重，上游调为 **-4**。不编入语法模块 = 白霜整句体验打折。
3. **librime 核心判空保护**：grammar 调用点位于 `poet.cc`
   `if (auto* grammar = Grammar::Require("grammar"))`，模块未编入或模型缺失时
   **静默降级**，schema 写 grammar 段无运行时风险。
4. **构建与分发基础设施就绪**：superbuild wire_plugin 机制、`build.sh ensure_*`
   拉源模式（Lua 先例）、AssetDeployer 递归部署（`grammar/zh-moqi.gram` 零改动落位
   shared dir，FallbackResourceResolver 优先 user dir、回退 shared dir 恰好命中）。

## 4. 移动端适配优化（已实施）

| 优化项 | 措施 | 收益 |
|---|---|---|
| KENLM_MAX_ORDER 16 → 8 | superbuild 预设 CACHE 变量（witogram 侧非 FORCE 声明，预设值生效） | 目标模型最高 8-gram（zh-moqi / wanxiang-lts），收窄查询状态数组，降低运行时内存 |
| 宿主工具关闭 | 沿用 superbuild `BUILD_TOOLS=OFF` FORCE | dump_to_arpa 等桌面工具不参与交叉编译，减体积减风险 |
| KenLM 测试关闭 | witogram CMake 已 FORCE `COMPILE_TESTS/COMPILE_TOOLS=OFF` | 同上 |
| 模型选型 | 首版仅随包 7MB 的 zh-moqi.gram（搭配惩罚信号），不上百 MB 级 .klm | APK 体积可控；mmap 分页加载使冷启动接近零延迟 |
| 静态链接 | 并入 librime.a（与 lua/predict 同通道） | 无 .so 加载与 LGPL 动态链接合规负担 |

未做（留作后续）：sentencepiece（BPE）官方实验结论"无独立价值"，当前随插件
一并编译属上游 CMake 硬依赖；若后续在意体积可向上游提 `WITH_BPE` 选项 PR。

## 5. 兼容性与维护成本评估

| 维度 | 结论 |
|---|---|
| 许可证 | witogram GPL-3.0、KenLM LGPL-2.1、sentencepiece Apache-2.0；ziyou-ime 为 GPL-3.0-or-later，**兼容**。静态链接后整体分发需保持 GPL 义务（本项目本就开源 GPL，无新增负担） |
| 配置兼容 | `grammar/language` 等配置段与 octagram 同构；上游 rime_frost.schema 无需改造 |
| 行为回归 | grammar 仅参与整句打分；T9（table 系方案）不经过 poet grammar 路径；既有 `max_homophones: 4` 用户词升权修复不受影响 |
| 上游活跃度 | 单一维护者（几维团队）；但 .gram 直读为 octagram 成熟代码移植，即使停更可冻结 pin 长期使用（已 pin `c6fe4525`） |
| 构建复杂度 | +2 个嵌套子模块（kenlm/sentencepiece），由 `build.sh ensure_witogram_sources` 幂等初始化；superbuild/ 仍整体 gitignore，版本化逻辑全部落在 build.sh + .gitmodules |
| 回退预案 | `WITH_WITOGRAM=OFF` 重编即回到现状；schema grammar 段保留无害（poet 判空降级）；亦可平滑切换回 octagram（组件名相同） |

## 6. 实施步骤（本分支已完成项）

1. **子模块纳管**：`.gitmodules` 新增 `librime-prebuilt/plugins/librime-witogram`
   （pin `c6fe4525`）；嵌套子模块 kenlm/sentencepiece 由
   `build.sh ensure_witogram_sources` 幂等浅克隆。
2. **构建接入**：
   - `build.sh`：新增 `WITH_WITOGRAM` 开关与 `ensure_witogram_sources`，透传
     `-DWITH_WITOGRAM`；
   - `superbuild/CMakeLists.txt`：新增 option、RIME_PLUGINS 登记、
     `KENLM_MAX_ORDER=8` 移动端预设、`rime-witogram-objs` 并入 Boost 补链名单；
   - `Makefile`：新增 `make witogram` 入口。
3. **schema 恢复**：`assets/rime/rime_frost.schema.yaml` 恢复 grammar 段
   （`language: zh-moqi` + `non_collocation_penalty: -4`）、translator 补
   `grammar_penalty: -4`，版本号 ziyou1 → ziyou2。
4. **模型部署**：`assets/rime/grammar/zh-moqi.gram`（7.0MB）随 AssetDeployer
   递归部署至 shared dir，witogram ResourceResolver 回退路径命中。
5. **测试**：`SchemaPenaltyConfigTest` 新增 grammar 段防回归守护用例；
   `.gitignore` 为 `superbuild/CMakeLists.txt` 开放例外（原整体忽略会丢失
   本次全部 CMake 定制）。
6. **App 侧同步**：JNI CMakeLists option/宏、`rime_jni.cc` 模块声明、
   `build.gradle.kts` 的 `-DWITH_WITOGRAM=ON`；rime_jni 补链系统 `z`。
7. **bundler 修复**：BundleStaticLibrary 补读 INTERFACE_LINK_LIBRARIES
   （sentencepiece 的 absl 依赖链），并纳入 boost program_options 随合并库打包。

## 7. 验证计划与结果

### 7.1 PoC 交叉编译（已完成，✅ EXIT=0）

`WITH_LUA=ON WITH_PREDICT=ON WITH_WITOGRAM=ON ./build.sh arm64-v8a` 全量通过，
`llvm-nm libs/arm64-v8a/librime.a` 确认 `rime_require_module_witogram` 已定义（T）。
Android 首批集成踩坑与修复（均落在主仓构建侧，子模块零补丁）：

| # | 问题 | 根因 | 修复 |
|---|---|---|---|
| 1 | kenlm 缺 boost 头（thread/ptr_container/random/program_options） | 模块化 Boost 子集未含 | Boost.cmake 条件追加 4 个子集 |
| 2 | `KENLM_MAX_ORDER` 未定义 | kenlm 仅挂 kenlm 目标，kenlm_util/witogram objs 漏 | superbuild 目录级 compile definition（兼移动端 16→8 优化） |
| 3 | sentencepiece init.cc 报 PACKAGE_STRING 未定义 → std::function 推导连锁错 | **glog 的 INTERFACE include 泄漏其二进制目录 config.h，截胡裸名 `#include "config.h"`**（桌面未暴露的移动端独有问题） | glog/glog_internal INTERFACE 收窄为 src/ 头目录 |
| 4 | JNI 链接 undefined: `inflate*` | kenlm gzip 路径依赖 zlib | rime_jni 补链系统 `z`（NDK 静态 libz.a） |
| 5 | JNI 链接 undefined: absl 符号 | sentencepiece-static 以 INTERFACE-only 声明 absl 依赖，bundler 递归不读 INTERFACE_LINK_LIBRARIES | BundleStaticLibrary 补读 INTERFACE 依赖链（78 个 absl 归档入合并清单） |

### 7.2 App 级集成验证（已完成，✅）

- `:app:assembleDebug` 成功（arm64-v8a，`-DWITH_WITOGRAM=ON` JNI 声明与库符号匹配）；
- APK 内容核验：`assets/rime/grammar/zh-moqi.gram`（7.0MB）在包；
  `rime_frost.schema.yaml` 的 `grammar:` 段完整。

### 7.3 单测门禁（已完成，✅）

- `SchemaPenaltyConfigTest` 新增 2 例（grammar 段不变量 + 模型随包守护）；
- 全量 599/599 绿，基线 `scripts/unit-test-baseline.txt` 已递增。

### 7.4 真机验证清单（待设备连接后执行）

1. `pm clear` + 全新部署，fullCheck 编译通过，rime.log 出现
   `successfully loaded GramDb for: zh-moqi`；
2. 全拼整句输入（长句多候选）排序符合预期，无卡顿；
3. T9 输入与用户词升权无回归；
4. 冷启动内存观察（mmap 分页，RSS 应远低于模型文件大小）。

构建日志存档：`/tmp/witogram-poc-build.log`。

## 8. 后续演进（不在本分支范围）

- 万象 LTS 大模型（.klm）走 ziyou-ime-dicts catalog `kind: grammar_model`
  下载通道（可行性方案 §5.3 已预留），替换随包 zh-moqi.gram；
- 上游 sentencepiece 依赖裁剪 PR（体积优化）；
- grammar 信号与 predict 联想联动的混合排序实验。
