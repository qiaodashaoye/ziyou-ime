#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
extract_predict_corpus.py —— 从 Rime 词典（*.dict.yaml）提取联想语料

用途：把「词表型」词典转换为 predict.db 构建管线可消费的词对 TSV
（prev<TAB>next<TAB>weight）。转换语义与官方 make_predict_data 一致：
对每个词 W 在每个切分点 i 生成一对 (W[:i], W[i:])——用户上屏 W 的前缀
后，引擎即可以前缀为键联想出剩余部分；配合构建期双键扩展与运行期
后缀回退覆盖整句上屏场景。

数据源要求：Rime 词典格式——`#` 注释 + `---` YAML 头 + `...` 之后为
`词<TAB>拼音<TAB>权重` 正文（拼音列可缺失，权重列可缺失）。

用法：
    python3 scripts/extract_predict_corpus.py \
        --dict-dir dist/corpus_src/cn_dicts/cn_dicts \
        --out dist/corpus_rimeice.tsv [--min-pct 50]

筛选与权重：
- 仅保留纯 CJK（基本区+扩展A）2~8 字词；
- 每部词典按权重排名裁剪（--min-pct N = 保留权重排名前 N% 的条目，
  默认 50 即前一半），控制产出规模与 db 体积；
- 权重映射为管线档位：前 5% →90，前 20% →70，前 60% →50，其余 →30
  （词典内相对分位，跨词典可比）。
"""

import argparse
import os
import sys

CJK_RANGES = ((0x4E00, 0x9FFF), (0x3400, 0x4DBF))
MIN_WORD_LEN, MAX_WORD_LEN = 2, 8


def is_cjk_word(s: str) -> bool:
    if not (MIN_WORD_LEN <= len(s) <= MAX_WORD_LEN):
        return False
    for ch in s:
        cp = ord(ch)
        if not any(lo <= cp <= hi for lo, hi in CJK_RANGES):
            return False
    return True


def parse_dict(path: str):
    """解析单个 *.dict.yaml，返回 [(word, weight), ...]（正文区）。"""
    entries = []
    in_body = False
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not in_body:
                if line == "...":
                    in_body = True
                continue
            if not line or line.startswith("#"):
                continue
            cols = line.split("\t")
            if len(cols) < 2:
                continue
            word = cols[0].strip()
            if not is_cjk_word(word):
                continue
            weight = 1
            # 权重在最后一列（兼容 词\t权重 / 词\t拼音\t权重 两种列式）
            try:
                weight = int(cols[-1].strip())
            except ValueError:
                weight = 1
            if weight <= 0:
                continue
            entries.append((word, weight))
    return entries


def tier_for(rank_pct: float) -> int:
    """词典内分位 → 管线权重档位。"""
    if rank_pct <= 5:
        return 90
    if rank_pct <= 20:
        return 70
    if rank_pct <= 60:
        return 50
    return 30


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dict-dir", required=True, help="*.dict.yaml 所在目录")
    ap.add_argument("--out", required=True, help="输出 TSV 路径")
    ap.add_argument("--min-pct", type=float, default=50.0,
                    help="按权重排名裁剪：保留权重排名前 N%% 的条目（默认 50）")
    args = ap.parse_args()

    files = sorted(
        os.path.join(args.dict_dir, f)
        for f in os.listdir(args.dict_dir)
        if f.endswith(".dict.yaml")
    )
    if not files:
        print(f"错误: {args.dict_dir} 下无 *.dict.yaml", file=sys.stderr)
        return 1

    total_words = 0
    total_pairs = 0
    kept_words = 0
    with open(args.out, "w", encoding="utf-8") as out:
        out.write("# 由 extract_predict_corpus.py 生成；源: rime-ice cn_dicts"
                  "（见 dist/corpus_src/ 溯源记录）\n")
        for path in files:
            name = os.path.basename(path)
            entries = parse_dict(path)
            total_words += len(entries)
            if not entries:
                print(f"[skip] {name}: 无有效词条")
                continue
            # 权重降序排名 → 分位裁剪与档位映射
            entries.sort(key=lambda x: x[1], reverse=True)
            n = len(entries)
            cutoff = int(n * args.min_pct / 100.0)
            kept = entries[:max(cutoff, 1)]
            kept_words += len(kept)
            for rank, (word, _w) in enumerate(kept):
                pct = 100.0 * rank / n
                tier = tier_for(pct)
                # 每个切分点产出一对 (前缀, 剩余)
                for i in range(1, len(word)):
                    out.write(f"{word[:i]}\t{word[i:]}\t{tier}\n")
                    total_pairs += 1
            print(f"[ok] {name}: 词条 {n}，保留 {len(kept)}"
                  f"（前 {args.min_pct:.0f}%），产出词对 "
                  f"{sum(len(w) - 1 for w, _ in kept)}")

    print(f"合计: 词条 {total_words} → 保留 {kept_words}，"
          f"词对 {total_pairs} → {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
