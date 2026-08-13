#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""自建 predict.db 构建管线（联想优化方案 §4.1/§4.2 构建期方案）。

把多来源语料合并、归一、双键扩展后打包为 librime-predict 可用的
predict.db（DARTS 双数组 trie + StringTable，只读 mmap），替代/并行
官方 data-1.0 包，抬升引擎级联想的召回率与质量。

数据来源（权重语义：同 key 内候选的相对强度，构建期排序依据）：
1. 种子语料   scripts/predict_seed_corpus.tsv（随仓库维护的高质量词对）
2. 外部语料   --corpus TSV（prev \\t next [\\t weight]，可多次传入，
               如开源词组库/wiki 语料抽取产物）
3. 采纳固化   --adoptions JSON（设备导出：{"prev":{"next":count}}，
               §4.6 形态 B——个性化以数据迭代进入静态表）
4. LLM 蒸馏   --distill（对探针词表中无语料覆盖的键，经 OpenAI 兼容
               chat/completions 端点离线批量生成候选；需环境变量
               LLM_API_URL / LLM_API_KEY / LLM_MODEL）

双键扩展（§4.2 构建期替代，免 native 改动）：对长度 ≥3 字的键追加
其 2~4 字尾部后缀键（权重按级衰减），使「长句上屏精确匹配 miss」
时仍能以尾词命中预测，覆盖 predict.db 纯精确匹配的最大召回缺口。

产出与报告：
- predict.db（默认 dist/predict.db，经 AssetDeployer 或词库下载管线分发）
- 覆盖率报告（stdout）：探针词表键覆盖率、键/条目总数、尾键占比、体积
- --tsv-out 可选导出合并后 TSV 供人工抽样质检

依赖：Python 3.8+ 标准库（urllib 做蒸馏请求）；打包阶段需要
build_predict 工具二进制（librime-prebuilt superbuild 产物，
默认查找 librime-prebuilt/build/bin/build_predict，或 --tool 指定）。

用法示例：
  python3 scripts/build_predict_db.py                        # 仅种子语料
  python3 scripts/build_predict_db.py --adoptions ad.json    # + 个性化固化
  python3 scripts/build_predict_db.py --distill --distill-limit 200
  python3 scripts/build_predict_db.py --out dist/predict.db --tool <path>
"""

import argparse
import json
import os
import sys
import subprocess
import urllib.request

# ===== 权重与体积常量 =====

# 种子/外部语料的默认条目权重（未显式给出 weight 列时）
DEFAULT_WEIGHT = 50.0
# 采纳固化词对的权重：count 归一化基准（count >= 此值即满分）
ADOPTION_COUNT_FULL = 5
# 采纳词对满分权重（高于通用语料：用户实测采纳 > 统计语料）
ADOPTION_WEIGHT_FULL = 90.0
# 蒸馏候选权重（云端模型生成，质量介于两者之间，需人工抽检）
DISTILL_WEIGHT = 60.0
# 尾键权重衰减系数（每多截一级乘一次，防尾键压过整词键）
TAIL_DECAY = 0.5
# 尾键最小长度（字）：低于此长度的后缀信息量不足，不生成
TAIL_MIN_LEN = 2
# 尾键最大长度（字）
TAIL_MAX_LEN = 4
# 单键候选条数上限（与候选栏容量和 db 体积预算对齐）
MAX_CANDIDATES_PER_KEY = 8
# 单键/单候选文本约束（与运行时词窗口/候选栏预算对齐）
MAX_KEY_CHARS = 8
MAX_CANDIDATE_CHARS = 20
# db 体积红线（字节）：超出给出告警（APK/部署预算 ≤30MB）
SIZE_WARN_BYTES = 30 * 1024 * 1024

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(SCRIPT_DIR)
SEED_CORPUS = os.path.join(SCRIPT_DIR, "predict_seed_corpus.tsv")
PROBE_WORDS = os.path.join(SCRIPT_DIR, "predict_probe_words.txt")
DEFAULT_TOOL_CANDIDATES = [
    # 宿主机 librime 构建产物（README-mac.md 路径，macOS 为 build/bin/Release）
    os.path.join(REPO_ROOT, "librime-prebuilt", "librime", "build", "bin", "Release", "build_predict"),
    os.path.join(REPO_ROOT, "librime-prebuilt", "librime", "build", "bin", "build_predict"),
    os.path.join(REPO_ROOT, "librime-prebuilt", "build", "bin", "build_predict"),
]


def is_cjk(ch):
    """CJK 统一表意文字（基本区 + 扩展 A 区），与运行时学习口径一致。"""
    return "\u4E00" <= ch <= "\u9FFF" or "\u3400" <= ch <= "\u4DBF"


def clean_text(text):
    """剥离首尾空白；返回清洗结果（不做内部改写，保词形原貌）。"""
    return text.strip()


def valid_entry(key, text):
    """键/候选合法性：非空、无空白（build_predict 以空白切列）、纯 CJK、长度有界。"""
    if not key or not text:
        return False
    if any(c.isspace() for c in key) or any(c.isspace() for c in text):
        return False
    if not all(is_cjk(c) for c in key) or not all(is_cjk(c) for c in text):
        return False
    if len(key) > MAX_KEY_CHARS or len(text) > MAX_CANDIDATE_CHARS:
        return False
    return True


def load_tsv_corpus(path, data, source_name):
    """读入 TSV 语料（prev \\t next [\\t weight]），合并进 data[key] = {text: weight}。"""
    added = 0
    with open(path, encoding="utf-8") as f:
        for lineno, line in enumerate(f, 1):
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 2:
                print(f"  [warn] {source_name}:{lineno} 列数不足，跳过", file=sys.stderr)
                continue
            key, text = clean_text(parts[0]), clean_text(parts[1])
            weight = float(parts[2]) if len(parts) >= 3 and parts[2] else DEFAULT_WEIGHT
            if not valid_entry(key, text) or weight <= 0:
                continue
            tails = data.setdefault(key, {})
            # 同键同词多来源：取最大权重（强源胜出，不做加权黑箱）
            tails[text] = max(tails.get(text, 0.0), weight)
            added += 1
    return added


def load_adoptions(path, data):
    """读入设备导出的采纳词对 JSON（§4.6 形态 B），count 归一化为权重。"""
    with open(path, encoding="utf-8") as f:
        raw = json.load(f)
    added = 0
    for prev, tails in raw.items():
        if not isinstance(tails, dict):
            continue
        for text, count in tails.items():
            try:
                count = int(count)
            except (TypeError, ValueError):
                continue
            if count <= 0 or not valid_entry(prev, text):
                continue
            weight = ADOPTION_WEIGHT_FULL * min(count, ADOPTION_COUNT_FULL) / ADOPTION_COUNT_FULL
            bucket = data.setdefault(prev, {})
            bucket[text] = max(bucket.get(text, 0.0), weight)
            added += 1
    return added


def distill_missing_keys(data, probe_words, limit):
    """对探针词表中无覆盖的键做 LLM 离线蒸馏（OpenAI 兼容 chat/completions）。

    离线批处理无延迟约束；每次请求批量问 8 个键以摊薄开销。
    需要环境变量 LLM_API_URL / LLM_API_KEY / LLM_MODEL；未配置则跳过并提示。
    """
    api_url = os.environ.get("LLM_API_URL", "")
    api_key = os.environ.get("LLM_API_KEY", "")
    model = os.environ.get("LLM_MODEL", "")
    if not (api_url and api_key and model):
        print("  [warn] 未配置 LLM_API_URL/LLM_API_KEY/LLM_MODEL，跳过蒸馏", file=sys.stderr)
        return 0
    if not api_url.startswith("https://"):
        print("  [warn] 蒸馏端点必须 HTTPS，跳过", file=sys.stderr)
        return 0
    missing = [w for w in probe_words if w not in data][:limit]
    if not missing:
        return 0
    system_prompt = (
        "你是中文输入法联想语料生成器。对给定的每个词，给出用户输入该词后"
        "最可能接着输入的候选词。要求：每个键一行，格式为「键\\t候选1,候选2,...」，"
        "候选 3~6 个，用英文逗号分隔；每个候选是 1~6 个汉字的词或短语，"
        "自然高频，不要重复，不要解释。"
    )
    added = 0
    batch = 8
    for start in range(0, len(missing), batch):
        keys = missing[start:start + batch]
        body = json.dumps({
            "model": model,
            "stream": False,
            "max_tokens": 600,
            "temperature": 0.4,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": "词列表：\n" + "\n".join(keys)},
            ],
        }).encode("utf-8")
        req = urllib.request.Request(
            api_url, data=body, method="POST",
            headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"},
        )
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
            content = payload["choices"][0]["message"]["content"]
        except Exception as e:  # 蒸馏是增强项：失败不阻断构建
            print(f"  [warn] 蒸馏请求失败（批次 {start // batch}）: {e.__class__.__name__}", file=sys.stderr)
            continue
        for line in content.splitlines():
            parts = line.split("\t")
            if len(parts) != 2:
                continue
            key = clean_text(parts[0])
            for cand in parts[1].split(","):
                text = clean_text(cand.strip(" \u3000、。"))
                if not valid_entry(key, text):
                    continue
                bucket = data.setdefault(key, {})
                bucket[text] = max(bucket.get(text, 0.0), DISTILL_WEIGHT)
                added += 1
    return added


def expand_tail_keys(data):
    """双键扩展：为长度 ≥3 的键生成 2~4 字尾后缀键（权重逐级衰减）。

    返回新增尾键数。已存在的键不覆盖（整词键优先），只补缺。
    """
    added = 0
    for key in [k for k in data.keys() if len(k) >= TAIL_MIN_LEN + 1]:
        candidates = data[key]
        for tail_len in range(min(TAIL_MAX_LEN, len(key) - 1), TAIL_MIN_LEN - 1, -1):
            suffix = key[-tail_len:]
            if suffix == key or suffix in data:
                continue
            decay = TAIL_DECAY ** (len(key) - tail_len)
            data[suffix] = {text: weight * decay for text, weight in candidates.items()}
            added += 1
    return added


def cap_and_sort(data):
    """每键候选按权重降序取前 MAX_CANDIDATES_PER_KEY 条。"""
    for key in data:
        top = sorted(data[key].items(), key=lambda kv: kv[1], reverse=True)
        data[key] = dict(top[:MAX_CANDIDATES_PER_KEY])


def coverage_report(data, probe_words):
    """探针覆盖率报告：高频词键命中率是自建 db 的首要验收指标。"""
    covered = sum(1 for w in probe_words if w in data)
    total_entries = sum(len(v) for v in data.values())
    return covered, total_entries


def build_db(data, out_path, tool_path):
    """调用 build_predict 打包：stdin 喂「key text weight」三列。"""
    lines = []
    for key in sorted(data.keys()):
        for text, weight in data[key].items():
            lines.append(f"{key}\t{text}\t{weight:.4f}\n")
    with subprocess.Popen(
        [tool_path, out_path], stdin=subprocess.PIPE,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
    ) as proc:
        out, _ = proc.communicate("".join(lines).encode("utf-8"))
        if proc.returncode != 0:
            print(out.decode("utf-8", errors="replace"), file=sys.stderr)
            raise RuntimeError(f"build_predict 退出码 {proc.returncode}")
    return os.path.getsize(out_path)


def find_tool(explicit):
    """定位 build_predict 二进制：--tool 显式指定 > 预置候选路径 > PATH。"""
    if explicit:
        if os.path.isfile(explicit) and os.access(explicit, os.X_OK):
            return explicit
        raise FileNotFoundError(f"--tool 指定的 build_predict 不可执行: {explicit}")
    for cand in DEFAULT_TOOL_CANDIDATES:
        if os.path.isfile(cand) and os.access(cand, os.X_OK):
            return cand
    from shutil import which
    found = which("build_predict")
    if found:
        return found
    raise FileNotFoundError(
        "未找到 build_predict 工具。打包需要宿主机可执行二进制（交叉编译的\n"
        "arm64-v8a 产物不可在宿主运行），请在宿主环境构建 librime 工具\n"
        "（macOS 步骤见 docs/联想功能优化调研与方案.md §7.7）：\n"
        "  brew install cmake boost\n"
        "  cd librime-prebuilt/librime && make deps && make\n"
        "产物位于 librime/build/bin/Release/build_predict，或以 --tool 指定。"
    )


def main():
    parser = argparse.ArgumentParser(description="自建 predict.db 构建管线")
    parser.add_argument("--corpus", action="append", default=[], metavar="TSV",
                        help="外部语料 TSV（prev\\tnext[\\tweight]），可多次")
    parser.add_argument("--no-seed", action="store_true", help="不合并种子语料")
    parser.add_argument("--adoptions", metavar="JSON", help="设备导出的采纳词对 JSON（§4.6 形态 B）")
    parser.add_argument("--distill", action="store_true", help="对探针缺失键做 LLM 蒸馏补全")
    parser.add_argument("--distill-limit", type=int, default=200, help="蒸馏键数上限（默认 200）")
    parser.add_argument("--no-tail-keys", action="store_true", help="关闭双键（尾后缀键）扩展")
    parser.add_argument("--probe", default=PROBE_WORDS, help="覆盖率探针词表（每行一词）")
    parser.add_argument("--out", default=os.path.join(REPO_ROOT, "dist", "predict.db"),
                        help="输出 predict.db 路径（默认 dist/predict.db）")
    parser.add_argument("--tool", help="build_predict 二进制路径")
    parser.add_argument("--tsv-out", metavar="FILE", help="可选：导出合并后 TSV 供质检")
    args = parser.parse_args()

    probe_words = []
    if os.path.isfile(args.probe):
        with open(args.probe, encoding="utf-8") as f:
            for line in f:
                w = clean_text(line)
                # 去重保序：重复探针会虚增覆盖率分母，口径必须唯一词
                if w and w not in probe_words:
                    probe_words.append(w)
    else:
        print(f"  [warn] 探针词表不存在: {args.probe}", file=sys.stderr)

    data = {}
    if not args.no_seed:
        n = load_tsv_corpus(SEED_CORPUS, data, "seed")
        print(f"[1/5] 种子语料: {n} 条")
    for corpus in args.corpus:
        n = load_tsv_corpus(corpus, data, os.path.basename(corpus))
        print(f"[1/5] 外部语料 {os.path.basename(corpus)}: {n} 条")
    if args.adoptions:
        n = load_adoptions(args.adoptions, data)
        print(f"[2/5] 采纳固化: {n} 条（个性化，权重提升）")
    else:
        print("[2/5] 采纳固化: 未提供 --adoptions，跳过")
    if args.distill:
        n = distill_missing_keys(data, probe_words, args.distill_limit)
        print(f"[3/5] LLM 蒸馏: {n} 条")
    else:
        print("[3/5] LLM 蒸馏: 未启用 --distill，跳过")

    tail_added = 0 if args.no_tail_keys else expand_tail_keys(data)
    cap_and_sort(data)
    covered, total_entries = coverage_report(data, probe_words)
    print(f"[4/5] 双键扩展: 新增尾键 {tail_added} 个；合计键 {len(data)}，条目 {total_entries}")
    if probe_words:
        rate = covered / len(probe_words) * 100
        print(f"      探针覆盖率: {covered}/{len(probe_words)} = {rate:.1f}%")

    if args.tsv_out:
        with open(args.tsv_out, "w", encoding="utf-8") as f:
            for key in sorted(data):
                for text, weight in data[key].items():
                    f.write(f"{key}\t{text}\t{weight:.4f}\n")
        print(f"      合并 TSV 已导出: {args.tsv_out}")

    tool = find_tool(args.tool)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    size = build_db(data, args.out, tool)
    print(f"[5/5] 打包完成: {args.out}（{size / 1024 / 1024:.2f} MB）")
    if size > SIZE_WARN_BYTES:
        print(f"  [warn] 体积超出 {SIZE_WARN_BYTES // 1024 // 1024}MB 红线，"
              f"建议改走词库下载管线分发或裁剪低权重条目", file=sys.stderr)


if __name__ == "__main__":
    main()
