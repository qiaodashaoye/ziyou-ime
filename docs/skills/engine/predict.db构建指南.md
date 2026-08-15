# predict.db 构建指南（Skill 化操作版）

> 版本：v2.1（自建完全替代官方目标对齐：基线锚点、双层验收门禁、内容安全过滤、权重参数化、合规补强）
> 对应 Skill：`build-predict-db`（`.qoder/skills/build-predict-db/`，随仓库分发）
> 面向：字由输入法开发者 / 语料维护者
> 范围：自建 `predict.db`（librime-predict 引擎级联想词库）的构建、更新、部署与验收全流程
> 前置阅读：[ARCHITECTURE.md](../../../ARCHITECTURE.md)「联想输入」小节、[联想功能优化调研与方案.md](../../联想功能优化调研与方案.md) §4.1/§4.2/§7.7
> 相关源码：[scripts/build_predict_db.py](../../../scripts/build_predict_db.py) · [scripts/predict_seed_corpus.tsv](../../../scripts/predict_seed_corpus.tsv) · [librime-predict/tools/](../../../librime-prebuilt/plugins/librime-predict/tools/)

---

## 0. 背景：predict.db 是什么

`predict.db` 是 librime-predict 插件的联想词库：**DARTS 双数组 trie（键）+ StringTable（值）**
的只读二进制文件，运行时 mmap 加载（`PredictDb::Load` 走 `OpenReadOnly`），查询为
`exactMatchSearch` 精确匹配 + 引擎侧最长后缀回退（整串 miss 时按 UTF-8 字逐截尾后缀重试，
最短保留 2 字，已随 2026-08 重编生效）。

**核心特性决定了构建方式**：

- 文件只读、不可运行时写入 → 所有变更必须**离线重建整包**替换；
- 键不可反向枚举（无 dump 工具）→ 官方包内容无法"解出来改几条"，也无法继承，
  **语料源文件（TSV/JSON）是唯一可编辑的事实源**；
- **目标定位：自建库完全替代官方 data-1.0**，获得语料可控、可扩展、
  可自定义（权重/排除/安全过滤）的联想能力。官方包不再是"并行选项"，
  仅作两项用途：初始验收基线（永久副本 `scripts/predict_official_baseline.db`，
  sha256 见伴存 manifest，永不覆盖/删除）与回滚点。

---

## 1. Skill 安装与使用

### 1.1 安装

Skill 位于项目级目录 `.qoder/skills/build-predict-db/`，**随仓库分发、无额外安装步骤**：

```
.qoder/skills/build-predict-db/
├── SKILL.md              # Skill 主文件（工作流与约束）
├── reference.md          # 参数全表与故障排查
└── scripts/build_db.py   # 编排脚本（构建/验收/环境检查）
```

`git pull` 拉到最新分支即获得最新 Skill。Qoder 打开本仓库后自动发现项目级 Skill。

### 1.2 使用方式（两种）

**方式一：Agent 对话触发（推荐）**——直接描述意图，Agent 自动应用 Skill 工作流：

```
重建 predict.db，合并这份外部语料 corpus.tsv 和设备导出的采纳数据 ad.json，
完成后做召回率验收
```

触发词：重建/更新/打包 predict.db、联想词库、召回率验收、采纳词对固化等。

**方式二：直接执行编排脚本**（CI 或终端手动）：

```bash
# 环境检查
python3 .qoder/skills/build-predict-db/scripts/build_db.py --check

# 构建 + A/B 验收一条龙
python3 .qoder/skills/build-predict-db/scripts/build_db.py \
    --corpus my_corpus.tsv --adoptions adoptions.json --verify
```

编排脚本是仓库构建管线 `scripts/build_predict_db.py` 的薄封装（不重复实现构建逻辑），
额外提供环境探测与召回率 A/B 自动化。参数全表见 Skill 的 [reference.md](../../../.qoder/skills/build-predict-db/reference.md)。

### 1.3 Skill 的版本管理与更新

- Skill 与本文档受 `docs/skills/README.md` 维护纪律约束：**行为变更必须成对提交**
  （Skill 脚本 + 配套文档一个 commit）；
- Skill 行为变更（参数/流程/红线）时：递增本文档头部版本号，
  并在文末「版本变更记录」追加一行；
- Skill 目录整体走 git 版本管理，回滚即 `git revert` 对应提交；
- 编排脚本保持标准库实现（Python 3.8+），不引入 pip 依赖，保证 CI 可复现。

---

## 2. 初次构建流程（从零开始）

### 2.1 环境要求

| 项 | 要求 | 说明 |
|---|---|---|
| Python | 3.8+ | 编排脚本与构建管线仅用标准库 |
| macOS / Linux 宿主机 | 可编译 C++ | 打包工具是**宿主原生二进制**，交叉编译的 arm64-v8a 产物不能在宿主运行 |
| CMake + Boost | brew install | 构建 librime 宿主工具链需要 |
| Xcode CLT（macOS） | `xcode-select --install` | 常见缺失项 |

**第一步永远是环境检查**（自动探测工具链与基线文件，报告缺什么）：

```bash
python3 .qoder/skills/build-predict-db/scripts/build_db.py --check
```

### 2.2 宿主 librime 工具链（一次性，约 20~30 分钟）

`--check` 报告工具缺失时执行（打包需要 `build_predict`、验收需要 `query_predict`，
均为插件自带工具，宿主构建时 BUILD_TOOLS 默认 ON）：

```bash
brew install cmake boost
# Apple Silicon 上 boost 依赖 icu4c 时需补链接路径
export LIBRARY_PATH=${LIBRARY_PATH}:/opt/homebrew/opt/icu4c/lib

cd librime-prebuilt/librime
make deps                          # 构建 vendored 依赖（glog/leveldb/marisa/opencc/yaml-cpp）
make                               # librime + 插件 + 工具

ls build/bin/Release/build_predict build/bin/Release/query_predict
```

产物路径已内置在编排脚本的探测列表，**无需手动指定 `--tool`**。

> 常见失败点：Boost 版本过新——改用仓库自带
> `librime-prebuilt/librime/install-boost.sh` 源码构建替代 brew 版。

### 2.3 首次打包（Skill 操作）

```bash
python3 .qoder/skills/build-predict-db/scripts/build_db.py
```

等价于仅种子语料构建，产出 `dist/predict.db` + `dist/predict.db.merged.tsv`（质检用）。
构建输出示例（各阶段计数 + 覆盖率报告）：

```
[1/5] 种子语料: 533 条
[2/5] 采纳固化: 未提供 --adoptions，跳过
[3/5] LLM 蒸馏: 未启用 --distill，跳过
[4/5] 双键扩展: 新增尾键 19 个；合计键 319，条目 552
      探针覆盖率: 142/584 = 24.3%
[5/5] 打包完成: dist/predict.db（0.03 MB）
```

> **注意**：仅种子语料的库体积与覆盖率都很小（319 键 vs 官方 7.2MB），
> 直接替换会造成召回断崖。**替换官方包之前必须先扩充语料**（见第 3 节），
> 并通过第 5.3 节的召回率 A/B 红线（相对提升 ≥50%）。

---

## 3. 数据准备要求

### 3.1 四类数据源与权重语义

权重是**同键内候选的相对强度**（构建期排序依据，引擎按 db 存储序透出）：

| 来源 | 传入方式 | 默认权重 | 说明 |
|---|---|---|---|
| 种子语料 | 内置 `scripts/predict_seed_corpus.tsv`（`--no-seed` 可关） | 行内指定，档位 90/70/50/30 | 随仓库维护的人工精选高频词对；只收人工可解释的自然搭配 |
| 外部语料 | `--corpus xxx.tsv`（可多次） | 50（无 weight 列时） | 开源词组库 / wiki 抽取产物；官方 `tools/make_predict_data`（Rust）可从 wiki 语料生成大规模语料 |
| 外部语料（免尾键） | `--plain-corpus xxx.tsv`（可多次） | 50（无 weight 列时） | 整句键语料（如诗词句对）：参与合并/过滤/截断但键不做双键扩展——整句精确匹配天然成立，后缀 miss 由运行期回退覆盖，尾键徒增体积 |
| 采纳固化 | `--adoptions xxx.json` | `90 × min(count,5)/5`（count≥5 满分） | 设备导出的真实采纳词对，权重最高——**用户实测采纳 > 统计语料** |
| LLM 蒸馏 | `--distill` | 60 | 对探针词表无覆盖的键经 OpenAI 兼容端点离线生成；质量介于两者之间，**需人工抽检** |

同键同词多来源时**取最大权重**（强源胜出，不做加权黑箱）。

### 3.2 TSV 格式规范（种子与外部语料通用）

```
prev<TAB>next[<TAB>weight]
```

- **编码**：UTF-8（无 BOM）；**分隔符**：制表符（TAB）；
- **注释行**：以 `#` 开头的行整体跳过；空行跳过；
- **列数**：≥2 列；weight 列可省略（默认 50），必须 >0；
- **键（prev）**：1~8 字纯汉字（CJK 基本区 `\u4E00-\u9FFF` + 扩展 A 区 `\u3400-\u4DBF`），
  不得含任何空白字符（`build_predict` 以空白切列）；
- **候选（next）**：1~20 字纯汉字，同样禁空白；
- 非法行**静默过滤**（列数不足会打 `[warn]`），不阻断构建——
  所以务必在构建后核对各阶段条数输出是否符合预期。

示例（节选自种子语料）：

```tsv
# 格式：prev \t next \t weight（weight 可省略，默认 50；同键内相对强度）
# 权重档位：90 极高频｜70 高频｜50 常规｜30 低频补充
我	们	90
我	想	70
我	明天	50
```

### 3.3 采纳固化 JSON 格式（设备导出）

设置页「AI 服务 → 导出采纳词对数据」产出（即 `prediction_adoptions.json` 的副本）：

```json
{ "前文词": { "被采纳词": 采纳次数, ... }, ... }
```

- 词对在设备端已按隐私口径过滤（仅 1~4 字纯汉字词对计数，见第 7.3 节）；
- 构建时再做一次合法性校验 + count 正整数校验，脏数据静默跳过。

### 3.4 探针词表（验收基线，非语料）

`scripts/predict_probe_words.txt`：每行一个高频上屏词（约 600 词，去重后参与统计），
反映真实高频上屏分布（日常口语/社交/办公混合）。构建时统计「探针词成为 db 键」
的比例作为**召回率首要验收指标**；维护纪律：**只增补不删减**，覆盖率只升不降。

### 3.5 数据验证与清洗

构建管线内置的清洗（无需额外步骤）：首尾空白剥离 → 纯 CJK 校验 → 长度边界校验 →
同键同词权重取大 → **内容过滤（blocklist/exclude，见 §3.6，双键扩展之前执行）** →
双键扩展 → 每键按权重降序截断至 **8 条候选**。

**人工质检步骤（强烈建议每次替换前执行）**：

1. 编排脚本构建时总是导出 `<out>.merged.tsv` 合并全量 TSV；
2. 抽样检查低权重（≤30）与蒸馏（60）条目，剔除怪词/敏感词；
3. 蒸馏产物尤其要抽检（云端模型输出无人工背书）。

### 3.6 内容安全与排除过滤（替代官方前必配）

自建库随 APK 分发全量用户，语料性质属**发布内容**（不同于个人学习数据），
大规模外部语料/蒸馏产物可能夹带敏感、低俗词条，人工抽检不可靠，
构建管线提供两道构建期硬过滤（`[4/6] 内容过滤` 阶段报告剔除量）：

| 参数 | 匹配语义 | 用途 |
|---|---|---|
| `--blocklist FILE` | 键**精确**命中剔整键；候选**包含**命中剔该条 | 内容安全：敏感词清单，包含匹配防变体绕过 |
| `--exclude FILE` | 键或候选**精确**匹配即剔除 | 策划性剔除：定点移除已知坏条目 |

词表格式：每行一词，`#` 注释与空行跳过。过滤在双键扩展**之前**执行，
防被剔除条目扩散到尾后缀键。维护纪律：blocklist 与探针/种子同为
**只增不删**的受控文件，入 git 管理。

---

## 4. 构建参数与原理参考

编排脚本的完整参数表、底层构建管线常量与故障排查集中在 Skill 的
[reference.md](../../../.qoder/skills/build-predict-db/reference.md)，此处只述原理要点。

### 4.1 双键扩展原理

predict.db 是纯精确匹配，「整句上屏」必然 miss。双键扩展为每个 ≥3 字的键
自动生成其 2~4 字**尾部后缀键**（权重按 `TAIL_DECAY^(截去字数)` 衰减），
使长句上屏仍能以尾词命中。已存在的键不覆盖（整词键优先，只补缺）。
它与引擎侧运行期后缀回退是**同一语义的构建期/运行期双保险**——
即使双键未覆盖，回退补丁（已编入 librime.a）也会逐字截后缀重试。

### 4.2 关键构建常量（均已参数化，改动需重评估）

以下源级权重与衰减参数自 v2.1 起均开放为 CLI 参数（`--default-weight` /
`--adoption-weight` / `--distill-weight` / `--tail-decay`，经编排脚本透传），
下表为默认值；调整会改变所有来源的相对排序，需重跑验收门禁：

| 常量 | 默认值 | 含义 |
|---|---|---|
| `DEFAULT_WEIGHT` | 50 | 未给 weight 的语料默认权重 |
| `ADOPTION_WEIGHT_FULL` / `ADOPTION_COUNT_FULL` | 90 / 5 | 采纳满分权重与 count 归一基准 |
| `DISTILL_WEIGHT` | 60 | 蒸馏候选权重 |
| `TAIL_DECAY` | 0.5 | 尾键权重每截一级的衰减系数（防尾键压过整词键） |

以下为硬约束（不参数化，与运行时预算对齐）：

| 常量 | 值 | 含义 |
|---|---|---|
| `TAIL_MIN_LEN` / `TAIL_MAX_LEN` | 2 / 4 | 尾后缀键长度范围 |
| `MAX_CANDIDATES_PER_KEY` | 8 | 每键候选上限（与候选栏容量、体积预算对齐） |
| `MAX_KEY_CHARS` / `MAX_CANDIDATE_CHARS` | 8 / 20 | 键/候选长度上限 |
| `SIZE_WARN_BYTES` | 30MB | 体积红线（超出告警：建议走词库下载管线或裁剪） |

### 4.3 build_predict 工具本体

[build_predict.cc](../../../librime-prebuilt/plugins/librime-predict/tools/build_predict.cc)
的输入协议极简：**stdin 逐行三列（空白分隔）`key text weight`**，
输出 `predict.db`（格式标记 `Rime::Predict/1.0`）。构建管线与编排脚本都经此协议
喂数据，一般不需要直接调用；手工调试示例：

```bash
printf '今天\t天气\t50\n今天\t晚上\t50\n' | build_predict /tmp/test.db
```

配套的 `query_predict`（召回率查询，编排脚本验收依赖它）与
`make_predict_data`（wiki 语料生成，Rust）同目录可用。

---

## 5. 后续更新机制

### 5.1 增量更新的正确姿势：源文件增补 + 全量重建

predict.db 是只读二进制、无补丁机制，**"增量"体现在语料源而非产物**：

1. 新语料追加进种子 TSV（入 git）或独立 `--corpus` 文件；
2. 采纳数据天然累积——设备端 `prediction_adoptions.json` 持续攒批，
   每次构建传最新导出的 JSON 即可；
3. **每次发布都从全部源文件全量重建**（构建是幂等纯函数，同输入必同输出）。

不要手工编辑 dist/ 产物；产物可随时从源文件重放。

### 5.2 版本管理与变更跟踪

- **入 git 的源**：种子语料、探针词表、构建脚本、外部语料 TSV（建议也入仓库
  或记录来源与 sha256，保证可复现）；
- **不入 git 的产物**：`dist/predict.db`（构建产物）、设备导出的采纳 JSON（含
  脱敏用户数据，用完即删，勿提交）；
- **db 版本溯源**：predict.db 格式无自定义版本字段（tag 固定 `Rime::Predict/1.0`），
  线上 db 与语料的对应关系靠提交纪律维护——替换 assets 包的提交必须记录
  产物 sha256 与语料源 commit（可选维护 `PREDICT_DB_CHANGELOG`），
  与 Conventional Commits 规范一致；
- 回滚：官方包有永久副本 `scripts/predict_official_baseline.db`（sha256 见
  `scripts/predict_baseline_manifest.txt`），自建历史版在 git 历史中可回。

### 5.3 质量控制：双层召回率验收（基线锚点 + 三层门禁）

**基线锚点**：验收基线恒为官方永久副本 `scripts/predict_official_baseline.db`，
**不随 `assets/predict.db` 被自建库替换而漂移**（v2.0 曾以 assets 为基线，
首次替换后验收即失效，v2.1 修正）。副本与 sha256 manifest 永不覆盖/删除。

验收由编排脚本自动化：

```bash
# 首次替换官方库：门禁①必跑；完全替代前门禁③必配长尾探针
python3 .qoder/skills/build-predict-db/scripts/build_db.py \
    --corpus new_corpus.tsv --verify \
    --probe-long scripts/predict_probe_words_long.txt

# 后续迭代：追加门禁②（上一版产物作回归基线）
python3 .qoder/skills/build-predict-db/scripts/build_db.py \
    --verify --db dist/predict.db --prev-db dist/predict.prev.db
```

三层门禁（底层为 `query_predict`，判定以候选 B：`--fallback`，
即引擎后缀回退生效后的线上真实行为为准）：

| 门禁 | 对照 | 红线 | 适用 |
|---|---|---|---|
| ① 首次替换 | 候选 B vs 官方基线 | 相对提升 ≥ +50% | 每次验收必跑 |
| ② 迭代回归 | 候选 B vs `--prev-db` 上一版 | 相对降幅 ≤ 2% | 替换后的每次更新 |
| ③ 长尾门禁 | 长尾探针候选 B vs 官方基线 | 相对降幅 ≤ 10% | **完全替代前必配** |

全部门禁达标（退出码 0）方可进入部署；任一不达标则补语料/蒸馏后
重走构建与验收。**长尾探针**（`scripts/predict_probe_words_long.txt`，
含整句上屏样本与低频长尾词）是证明「替代官方后长尾不塌」的唯一手段——
官方 db 键不可枚举，等价性只能靠行为探针证明；该词表与高频探针同为
只增不删的受控文件，**待首次真实打包前必须构建**。

另两项构建期门禁：

- **探针覆盖率只升不降**（与探针词表"只增补不删减"纪律配套）；
- **体积 ≤30MB**（超出即告警：改走词库下载管线分发或裁剪低权重条目）。

---

## 6. 构建后部署使用

### 6.1 集成与部署流程

替换是**纯文件级操作**：schema（`predictor/db: predict.db`）、引擎代码、
JNI 层均零改动，predictor 仍从用户目录按同名加载。
`AssetDeployer` 负责把 `assets/predict.db` 部署到 Rime 用户目录，
**以 versionCode 变化触发**，且 `copyAssetFile` 为无条件覆盖写——
存量用户升级即收到新库：

```bash
# 1. 覆盖官方包（官方副本永久保留在 scripts/predict_official_baseline.db，可回滚）
cp dist/predict.db app/src/main/assets/predict.db

# 2. app/build.gradle.kts 的 versionCode +1（必须！否则不会重新部署）

# 3. 提交（信息记录产物 sha256 与语料源 commit，见 §5.2 溯源约定）

# 4. 构建装机
./gradlew :app:assembleDebug
```

> 编排脚本验收通过后会打印同样的部署提示，照抄即可。

### 6.2 真机冒烟清单

1. **加载确认**：`adb logcat | grep -i "predict"` 看到 predictor 加载日志，
   或核对用户目录 predict.db 的体积/mtime 已变为新库（防部署未生效的假验收）；
2. 输入探针词（如「今天」「谢谢」）→ commit 后候选栏出预测词（**强调色整栏**）；
3. 点选预测词 → 链式继续（最多 `max_iterations=3` 轮）；
4. 句末标点上屏 → 引擎预测清空（设计如此；LLM 开启时由续写补位）；
5. 长句上屏 → 以尾词命中预测（双键 + 后缀回退生效的证据）；
6. BackSpace/Escape → 清预测态。

### 6.3 运行时效果评估

埋点已入库（`LlmPredictionStats`，仅计数与延迟、无用户词内容）：

```bash
adb shell ime set com.ziyou.ime/.ZiYouInputMethodService
scripts/llm_prediction_metrics.sh --follow     # 边打字边采集（收起键盘时输出一行）
```

输出与验收口径：

```
LLM 预测统计: hits=18 misses=12 hitRate=60.0% chain=4/6 prefetch=3/5 reqs=7 p50ms=620
              engineShown=9 gap=3 llmAdopt=2/1
```

| 指标 | 验收线 | 含义 |
|---|---|---|
| hitRate | ≥40% | LLM 缓存命中率 |
| p50ms | <1000 | 首条候选感知延迟中位数 |
| chain | ≥60% | 采纳后链式第二轮缓存命中比 |
| `gap/(engineShown+gap)` | 观察项 | 上屏后无联想的空档占比（句末兜底方案立项依据） |
| `llmAdopt=with/without` | 观察项 | LLM 词采纳时引擎候选在场分布（位置策略依据） |

---

## 7. 最佳实践与注意事项

### 7.1 常见问题与解决方案

完整故障排查表见 Skill 的 [reference.md](../../../.qoder/skills/build-predict-db/reference.md)，高频项：

| 问题 | 对策 |
|---|---|
| `--check` 报告工具缺失 | 按 §2.2 构建宿主工具链（`make deps && make`） |
| 语料行被静默丢弃 | 违反格式规范（含空白/非汉字/超长）；对照 `.merged.tsv` 排查 |
| A/B 未达标 | 补外部语料或启用蒸馏后重走构建与验收 |
| 装机后联想无变化 | 忘记 versionCode +1；或杀进程重进使引擎重新加载 |

### 7.2 性能优化建议

- **准确优先于丰富**：联想遵循普适概率、分布平缓，堆候选数不如提高基础条件概率；
  不准则不如不出（每键 8 条上限即此纪律的落地）；
- 权重档位保持人工可解释（90/70/50/30），避免引入不可解释的加权黑箱；
- 大语料优先控制**键空间质量**而非体积——db 体积进 APK 预算，
  超 30MB 应转词库下载管线旁路分发；
- 蒸馏按 `--distill-limit` 控量，先小批量抽检质量再放量。

### 7.3 数据隐私、安全与合规

- **采纳数据**：设备端仅记录 1~4 字纯汉字词对计数（`AdoptionRecord.isLearnableWord`
  强制过滤），不含语句上下文、不进日志；导出经用户主动点击（设置页入口），
  构建用完即删，**不得提交 git、不得上传**；
- **蒸馏请求**：只发送探针词表（公开高频词），**不发送任何用户数据**；
  端点强制 HTTPS；
- **内容安全（替代官方后为发布内容红线）**：必须配置 `--blocklist`（见 §3.6），
  并将「内容过滤报告无异常剔除量 + 抽检通过」列入发布前 checklist；
- **外部语料许可证清单**（替代官方后语料规模大增，合规权重上升）：
  1. 引入前确认许可证与商用分发兼容（警惕 GPL 传染、CC 署名/非商用条款）；
  2. 保留来源清单与 sha256（防投毒，与扩展词库供应链同口径），
     建议入仓库伴存 manifest 或语料文件头注释；
  3. 需要署名的语料在发布说明/关于页致谢；
- 产物 predict.db 仅含词对与权重，不含任何用户原文，可安全随 APK 分发。

### 7.4 一句话流程速查（Skill 版）

```
--check 环境 → 备语料（TSV/采纳 JSON）+ 内容安全词表 → build_db.py 构建
→ --verify 三层门禁（≥50% / 不回归 / 长尾不塌）→ cp 进 assets + versionCode+1
+ 提交记 sha256/语料 commit → assembleDebug 装机 → 加载确认 + 冒烟 + 运行时指标
```

---

## 版本变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v2.1 | 2026-08-13 | 自建完全替代官方目标对齐：新增官方基线永久副本（验收基线不再随 assets 替换漂移）与双层验收门禁（首次替换/迭代回归/长尾）；构建管线新增 --blocklist 内容安全过滤与 --exclude 排除；源级权重参数化（4 个 CLI 项）；db 版本溯源约定；部署节补充纯文件级替换属性与加载确认；许可证清单强化 |
| v2.0 | 2026-08-13 | Skill 化重构：手动构建/验收命令收编进 `build-predict-db` Skill 编排脚本；新增 §1 Skill 安装与使用；文档迁入 `docs/skills/engine/` 分类目录 |
| v1.0 | 2026-08-13 | 初版：手动命令版构建指南 |
