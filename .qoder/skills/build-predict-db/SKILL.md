---
name: build-predict-db
description: 字由输入法自建 predict.db 联想词库的构建、更新与验收编排，目标是完全替代官方 data-1.0 包：合并种子/外部/采纳语料、内容安全过滤、双键扩展、双层召回率 A/B 验收、部署集成。当用户提到重建/更新/打包 predict.db、自建替代官方联想库、联想召回率验收、或需要合并采纳词对固化语料时使用。
---

# build-predict-db

编排字由输入法 `predict.db`（librime-predict 引擎级联想词库）的构建、更新与验收全流程。
**目标定位：自建库完全替代官方 data-1.0**，获得语料可控、可扩展、可自定义的联想能力；
官方包仅作初始基线与回滚点（永久副本 `scripts/predict_official_baseline.db`）。
完整背景与数据格式规范见配套文档 [docs/skills/engine/predict.db构建指南.md](../../../docs/skills/engine/predict.db构建指南.md)。

## 工作流

复制此清单跟踪进度：

```
任务进度：
- [ ] 步骤 1：检查 build_predict 工具链
- [ ] 步骤 2：准备/确认语料源与内容安全词表
- [ ] 步骤 3：构建 predict.db
- [ ] 步骤 4：双层召回率验收（首次替换红线 ≥50% / 迭代回归 / 长尾门禁）
- [ ] 步骤 5：部署集成（assets + versionCode + 溯源记录）
```

### 步骤 1：检查工具链

优先直接跑编排脚本的 `--check`（自动探测工具并报告环境状态）：

```bash
python3 .qoder/skills/build-predict-db/scripts/build_db.py --check
```

报告工具缺失时，按以下命令一次性构建宿主 librime 工具链（交叉编译产物不可在宿主运行）：

```bash
brew install cmake boost
export LIBRARY_PATH=${LIBRARY_PATH}:/opt/homebrew/opt/icu4c/lib   # Apple Silicon
cd librime-prebuilt/librime && make deps && make
```

### 步骤 2：确认语料源

- 种子语料 `scripts/predict_seed_corpus.tsv` 始终合并（除非显式 `--no-seed`）；
- 外部语料：向用户确认 TSV 路径（格式 `prev<TAB>next[<TAB>weight]`，纯汉字）；
  引入前确认许可证兼容性（见配套文档 §7.3）；
- 采纳固化：确认设备导出 JSON 路径（设置页「导出采纳词对数据」产物）。
  **隐私红线：该文件含脱敏用户数据，禁止提交 git、禁止上传**；
- 蒸馏（可选）：需 `LLM_API_URL`/`LLM_API_KEY`/`LLM_MODEL` 环境变量，端点必须 HTTPS；
- **内容安全词表**（替代官方前必配）：`--blocklist` 敏感词清单路径；
  另按需 `--exclude` 策划性排除词表。

### 步骤 3：构建

```bash
python3 .qoder/skills/build-predict-db/scripts/build_db.py \
    --corpus my_corpus.tsv --adoptions adoptions.json
```

脚本默认输出 `dist/predict.db` 与 `dist/predict_merged.tsv`（质检用），
并打印探针覆盖率。**仅种子语料的库（~319 键）禁止直接替换官方 7MB 包**——
覆盖率报告会明显偏低，先补语料。

### 步骤 4：双层验收（必须全部门禁达标才能部署）

```bash
# 首次替换官方库：门禁①（相对官方基线 ≥50%）必跑；完全替代前门禁③必配长尾探针
python3 .qoder/skills/build-predict-db/scripts/build_db.py \
    --verify --db dist/predict.db --probe-long scripts/predict_probe_words_long.txt

# 后续迭代：追加门禁②（相对上一版召回不降，容忍 -2%）
python3 .qoder/skills/build-predict-db/scripts/build_db.py \
    --verify --db dist/predict.db --prev-db dist/predict.prev.db
```

基线恒为官方副本 `scripts/predict_official_baseline.db`（永不随 assets 替换漂移）。
门禁未全部达标则补语料/蒸馏后重走步骤 2~4。

### 步骤 5：部署集成

验收通过后执行（纯文件级替换，schema 与引擎代码零改动；脚本末尾会打印同样提示）：

```bash
cp dist/predict.db app/src/main/assets/predict.db   # 官方副本在 scripts/ 永久保留，可回滚
# app/build.gradle.kts 的 versionCode +1（必须！AssetDeployer 以版本变化触发重部署）
# 提交信息记录产物 sha256 与语料源 commit（db 版本溯源）
./gradlew :app:assembleDebug
```

真机冒烟与运行时指标验收见配套文档 §6。

## 约束与纪律

- **准确优先于丰富**：每键候选 ≤8 条；低质量语料走 `--corpus`/`--distill`，不混入种子；
- 探针词表与种子语料**只增不删**，覆盖率只升不降；
- **内容安全**：替代官方分发全量用户前必须配置 `--blocklist`（语料随 APK 分发属发布内容）；
- 产物体积红线 30MB；构建幂等——全量重建自源文件，不手工编辑 dist/ 产物；
- **基线锚点**：`scripts/predict_official_baseline.db` 永不覆盖/删除（验收与回滚双锚点）；
- 替换 assets 包的提交需记录产物 sha256、语料源 commit、键/条目数、覆盖率与验收数据。

## 详细参考

- 参数全表、构建常量、故障排查：[reference.md](reference.md)
- 数据格式规范、更新机制、隐私安全：[../../../docs/skills/engine/predict.db构建指南.md](../../../docs/skills/engine/predict.db构建指南.md)
