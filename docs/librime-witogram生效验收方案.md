# librime-witogram 语法模型生效验收方案

> 适用分支：`feat/librime-witogram-integration`
> 验收对象：witogram 模块编入 + `grammar/zh-moqi.gram` 部署 + `rime_frost` grammar 段生效
> 前置阅读：`docs/librime-witogram集成可行性分析报告.md`（日志文案与降级行为的事实依据均以 witogram 源码为准）

验收顺序按成本递增：① 静态符号 → ② 配置资产 → ⑤ 单测门禁（均可离线秒级完成）
→ ④ 部署日志 → ③ 运行时 A/B（需真机/模拟器）。

---

## 1. 编译产物验证（librime.a 符号）

witogram 模块注册的符号为 `rime_require_module_witogram`（C++ 修饰名
`_Z28rime_require_module_witogramv`）。

```bash
cd ziyou-ime
NM=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-nm

# 判定：必须出现 " T _Z28rime_require_module_witogramv"（T = 已定义）
$NM libs/arm64-v8a/librime.a | grep rime_require_module_witogram | sort -u
```

**判读**：
- `T ...witogramv` → ✅ 已编入；
- 仅 `U ...witogramv` → ❌ 只有引用无定义（JNI 声明了但库没编，链接期会失败）；
- 无任何输出 → ❌ `WITH_WITOGRAM=OFF` 编的库，重跑
  `WITH_LUA=ON WITH_PREDICT=ON WITH_WITOGRAM=ON ./build.sh arm64-v8a`。

**辅助核验**（合并清单与体积）：

```bash
grep -c absl librime-prebuilt/build/arm64-v8a/rime.ar   # ≥70：sentencepiece/absl 链已入包
$NM libs/arm64-v8a/librime.a | grep -c "GramDb"          # >0：.gram 直读代码在库中
```

多 ABI 发布前，对每个 ABI 重复上述命令（当前仅 arm64-v8a 已重编）。

---

## 2. Schema 配置验证（grammar 段已加载）

### 2.1 静态一致性（三处必须同时满足）

```bash
cd ziyou-ime
# ① 源码 schema
grep -A2 "^grammar:" app/src/main/assets/rime/rime_frost.schema.yaml
# 期望： language: zh-moqi / non_collocation_penalty: -4
grep -m1 "grammar_penalty" app/src/main/assets/rime/rime_frost.schema.yaml
# 期望： grammar_penalty: -4

# ② APK 内资产（防打包遗漏）
unzip -p app/build/outputs/apk/debug/app-debug.apk \
  assets/rime/rime_frost.schema.yaml | grep -A2 "^grammar:"
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep zh-moqi.gram
# 期望：7339052 字节

# ③ 单测守护（见 §5）
./gradlew :app:testDebugUnitTest \
  --tests "com.ziyou.ime.config.SchemaPenaltyConfigTest"
```

### 2.2 部署落位（真机，确认 AssetDeployer 递归拷贝生效）

```bash
PKG=$(adb shell pm list packages | grep ziyou | sed 's/package://')
adb shell "run-as $PKG ls -l files/rime/grammar/"
# 期望：zh-moqi.gram 7339052 字节
```

模型缺失不会崩溃（witogram 降级路径），但惩罚机制静默失效，故此步必查。

### 2.3 引擎侧确认编译产物（fullCheck 通过即语法解析成功）

schema 有语法错误时部署会在 logcat 报 `error parsing schema`；无报错且
`rime_frost` 出现在可选方案即视为已加载（配合 §4 日志进一步确认实例化）。

---

## 3. 运行时行为验证（候选排序 A/B）

grammar 信号只影响**整句/多候选排序**（poet beam search），对 T9 与
单字输入无作用，用例必须选全拼长句。

### 3.1 A/B 开关手段（两种，任选）

| 手段 | 操作 | 含义 |
|---|---|---|
| **关模型**（推荐，隔离模型因素） | `adb shell "run-as $PKG mv files/rime/grammar/zh-moqi.gram files/rime/grammar/zh-moqi.gram.off"` 后重启输入法进程 | grammar 段仍在，collocation 惩罚按"无模型"路径走 |
| **关配置**（隔离配置因素） | 临时 patch 删除 `grammar:` 段并 `rime_patch`/手工覆盖 user dir 的 `rime_frost.custom.yaml` 无法删段，需直接改 shared dir 文件后触发重部署 | 对照组完全无 grammar 信号 |

恢复：改回文件名/内容，`am force-stop` 输入法或清缓存重部署。

### 3.2 测试用例（全拼键盘，逐键输入不分词上屏）

观察**首位候选**与整句候选组合的差异（B 组=有 grammar 时的期望行为）：

| # | 输入 | 场景 | 有 grammar 信号时的期望 |
|---|---|---|---|
| 1 | `yiwanshangyizhi` | 整句歧序 | 「一湾浅绿/意味深长」类搭配排序稳定，生造组合沉底 |
| 2 | `tazaiyinxingjiankangfangmian` | 长句跨词边界 | 整句切分倾向高频搭配（"身体健康方面"），`grammar_penalty: -4` 抑制非常规切分 |
| 3 | `feichanghaoyong` | 搭配检测 | "非常好用" 整体优先于 "飞长好用" 类 non-collocation 组合（`non_collocation_penalty: -4` 生效面） |
| 4 | `shuzijingji` vs `qiuzhijingli` | 历史事故回归 | 与迁移基线一致（模糊音/惩罚标签体系未被 grammar 改变） |
| 5 | 用户词场景 | 回归 | 上屏过的用户词仍正常升权（`max_homophones: 4` 不受 grammar 影响） |

**判读**：B 组与 A 组首位候选/整句切分**存在可复现差异**，且 B 组更符合
口语搭配 → grammar 信号生效。若两组完全一致，转 §4 查实例化日志。

> 注：zh-moqi.gram 为 7MB 小模型，上游定位为"让惩罚参数生效的钥匙"，
> 单点差异可能细微；用例 3 的搭配惩罚是最敏感观察点。

---

## 4. 日志与调试（实例化确认）

ziyou 的 `traits.log_dir = ""`（rime_jni.cc），rime/glog 日志输出到
**logcat**（无 rime.log 文件）。

### 4.1 抓取命令

```bash
PKG=$(adb shell pm list packages | grep ziyou | sed 's/package://')
adb logcat -c
adb shell am force-stop $PKG        # 冷启动，确保走引擎初始化
# 唤起键盘后：
adb logcat | grep -iE "gramdb|grammar|kenlm|witogram|module"
```

### 4.2 判读表（文案逐字来自 witogram 源码）

| 日志 | 级别 | 含义 |
|---|---|---|
| `registering components from module 'witogram'` | INFO | ✅ 模块注册（`rime_require_module_witogram` 被 JNI 拉起） |
| `use grammar: zh-moqi` | INFO | ✅ schema grammar 段被读到 |
| `loading gram db: .../grammar/zh-moqi.gram` | INFO | 开始加载 .gram |
| `successfully loaded GramDb for: zh-moqi` / `successfully loaded GramDb: <path>` | INFO | ✅✅ **核心验收行**：模型加载成功 |
| `GramDb not available for: zh-moqi (resolver may not find .gram file)` | INFO | ❌ 模型路径未命中 → 回 §2.2 查部署 |
| `failed to load GramDb: zh-moqi` | ERROR | ❌ 文件损坏/格式不符（校验 §1 辅助项与资产字节数） |
| `failed to load KenLM model: zh-moqi` | ERROR | 预期内（无 .klm），不阻断 .gram 路径 |
| 无任何 witogram 行 | — | ❌ 模块未注册：核对 §1 符号 + gradle/JNI 的 `-DWITH_WITOGRAM=ON` 一致性 |

glog INFO 默认开启，无需额外 verbose 参数；若日志被淹没，可用
`adb logcat -s libc:* RimeJNI:*` 缩窄。

---

## 5. 回归测试覆盖（防破坏门禁）

### 5.1 既有守护（本分支已落地）

`SchemaPenaltyConfigTest` 两例纯 JVM 资产断言，无需设备：

| 用例 | 拦截的破坏 |
|---|---|
| `白霜方案必须保留 grammar 段（整句组词惩罚依赖）` | schema 同步/裁剪误删 `language: zh-moqi`、`non_collocation_penalty: -4`、`grammar_penalty: -4`（grammar_penalty 用 `startsWith` 匹配以容忍行内注释） |
| `语法模型文件必须随 assets 部署` | zh-moqi.gram 被移除或体积越界（5~10MB 区间，防损坏/误替换） |

### 5.2 门禁链路（三层，任一破坏都会在 CI/本地暴露）

1. **用例级**：`./gradlew :app:testDebugUnitTest --tests "*SchemaPenaltyConfigTest"`；
2. **基线级**：`scripts/unit-test-baseline.txt`（当前 599，只增不减），
   `scripts/build-release.sh` 从 JUnit XML 累加实测数，低于基线即失败——
   删除用例数会直接打穿门禁；
3. **发版级**：versionCode（当前 13）为 AssetDeployer 部署键，schema/模型
   变更必须随升版，否则存量用户拿不到新资产（该纪律写入 build.gradle.kts
   注释）。

### 5.3 未来变更检查单

- 重编 librime.a 后跑 §1（符号可能随开关回退丢失）；
- 升级 witogram 子模块后跑 §1 + §4.2 全表（上游文案变化需同步本文件）；
- 任何触碰 `rime_frost.schema.yaml` / assets 的 PR 必须保持 599+ 绿。

---

## 附：一键冒烟脚本（可落 scripts/verify_witogram.sh）

```bash
#!/usr/bin/env bash
set -euo pipefail
NM="${ANDROID_NDK_HOME:?}/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-nm"
ABI="${1:-arm64-v8a}"
$NM libs/$ABI/librime.a | grep -q "T _Z28rime_require_module_witogramv" \
  && echo "[OK] witogram 符号就位($ABI)" || { echo "[FAIL] 符号缺失"; exit 1; }
grep -q "non_collocation_penalty: -4" app/src/main/assets/rime/rime_frost.schema.yaml \
  && echo "[OK] grammar 段配置" || { echo "[FAIL] grammar 段缺失"; exit 1; }
[ -f app/src/main/assets/rime/grammar/zh-moqi.gram ] \
  && echo "[OK] 模型资产在仓" || { echo "[FAIL] 模型缺失"; exit 1; }
./gradlew :app:testDebugUnitTest --tests "*SchemaPenaltyConfigTest" -q
echo "[OK] 全部静态验收通过"
```
