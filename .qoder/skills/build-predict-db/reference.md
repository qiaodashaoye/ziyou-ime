# build-predict-db 参考手册

SKILL.md 的详细参考：编排脚本参数、验收门禁协议、底层构建常量、故障排查。

## 编排脚本 build_db.py 参数

位置：`scripts/build_db.py`（相对本 Skill 目录）。标准库实现，无 pip 依赖；
构建逻辑全部委托仓库的 `scripts/build_predict_db.py`（不重复实现）。

| 参数 | 说明 |
|---|---|
| `--check` | 只检查环境：Python 版本、build_predict / query_predict 工具探测、官方基线副本与探针词表是否就位；不构建 |
| `--corpus TSV` | 外部语料（透传，可多次） |
| `--plain-corpus TSV` | 免尾键扩展的外部语料（透传，可多次）：参与合并/过滤/截断，键不做双键扩展（诗词句对等整句键专用） |
| `--adoptions JSON` | 设备导出的采纳词对 JSON（透传） |
| `--distill` / `--distill-limit N` | LLM 蒸馏开关与键数上限（透传；需环境变量） |
| `--no-seed` / `--no-tail-keys` | 关种子语料 / 关双键扩展（透传） |
| `--blocklist FILE` | 内容安全词表（透传）：键精确命中剔整键，候选包含命中剔该条 |
| `--exclude FILE` | 排除词表（透传）：键/候选精确匹配即剔除 |
| `--default-weight` / `--adoption-weight` / `--distill-weight` / `--tail-decay` | 源级权重参数（透传，缺省用管线默认值 50/90/60/0.5） |
| `--out FILE` | 输出路径（默认 `dist/predict.db`） |
| `--tool PATH` | 显式指定 build_predict（默认按预置候选路径与 PATH 探测） |
| `--verify` | 构建后执行双层召回率验收；全部门禁达标才退出码 0 |
| `--db FILE` | `--verify` 单独使用时的候选 db 路径（跳过构建直接验收） |
| `--prev-db FILE` | 门禁②迭代回归基线：上一版产物（容忍 -2%） |
| `--probe-long FILE` | 门禁③长尾探针词表（容忍 -10%；完全替代官方前必配） |
| `--probe FILE` | 高频探针词表（默认 `scripts/predict_probe_words.txt`） |

退出码：0 成功（验收模式 = 全部门禁达标）；非 0 失败或未达标。

构建模式总是顺带产出 `--tsv-out <out>.merged.tsv` 供人工质检。

## 验收基线与门禁协议

基线恒为 **`scripts/predict_official_baseline.db`**（官方 data-1.0 永久副本，
sha256 见伴存 `scripts/predict_baseline_manifest.txt`）——不随
`assets/predict.db` 被自建库替换而漂移；副本缺失时降级用 assets 并告警。

| 门禁 | 对照 | 红线 | 适用 |
|---|---|---|---|
| ① 首次替换 | 候选 B（+回退）vs 官方基线 | 相对提升 ≥ +50% | 每次验收必跑 |
| ② 迭代回归 | 候选 B vs `--prev-db` 上一版 | 相对降幅 ≤ 2% | 替换后的每次更新 |
| ③ 长尾门禁 | 长尾探针候选 B vs 官方基线 | 相对降幅 ≤ 10% | 完全替代前必配 |

判定以候选 B（`--fallback`，对应引擎后缀回退生效后的线上行为）为准。

## 底层构建管线参数（scripts/build_predict_db.py）

编排脚本透传上述全集；源级权重常量默认值（50/90/60/0.5）与其他常量
（`MAX_CANDIDATES_PER_KEY=8`、键 ≤8 字候选 ≤20 字、体积红线 30MB 等）
见配套文档 [docs/skills/engine/predict.db构建指南.md](../../../docs/skills/engine/predict.db构建指南.md) §4。

## query_predict 输出协议（验收解析依据）

```
db=<路径> fallback=on|off total=<探针数> hits=<命中数> rate=<百分比>%
```

编排脚本以 `rate=` 正则提取命中率，按上表门禁协议计算相对提升/降幅。

## 故障排查

| 现象 | 原因与对策 |
|---|---|
| `未找到 build_predict` | 宿主工具链未构建：`brew install cmake boost` + `cd librime-prebuilt/librime && make deps && make`（Apple Silicon 先 `export LIBRARY_PATH=${LIBRARY_PATH}:/opt/homebrew/opt/icu4c/lib`） |
| Boost 编译错误 | brew 版过新，改用 `librime/install-boost.sh` 源码构建 |
| 语料条数不符预期 | 违反格式规范被静默过滤（含空白/非汉字/超长/weight≤0）或被 blocklist/exclude 剔除；对照 `.merged.tsv` 与 `[4/6] 内容过滤` 输出排查 |
| 覆盖率不升反降 | 误删探针词或种子条目（两者纪律均为只增不删）；检查 git diff |
| 门禁①未达标 | 补外部语料或启用蒸馏后重走构建与验收；另备长短句探针验证回退效果 |
| 门禁②回归 | 对照上一版语料 diff 定位被移除的高频键；检查 blocklist 误伤 |
| 门禁③长尾塌方 | 高频探针达标但长尾不足：扩充长尾语料/增大语料规模后重建 |
| 基线降级告警 | 官方副本缺失：从 git 历史恢复 `scripts/predict_official_baseline.db` 并校验 manifest sha256 |
| 装机后联想无变化 | 忘记 versionCode +1；或杀进程重进使引擎重新加载 |
| 蒸馏跳过 | 环境变量缺失或端点非 HTTPS |
