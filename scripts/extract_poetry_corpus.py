#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
extract_poetry_corpus.py —— 从 chinese-poetry 数据集提取「一句诗联想整首诗」语料

数据源：https://github.com/chinese-poetry/chinese-poetry（MIT 许可）
- 全唐诗 全唐诗/poet.tang.*.json（约 5.5 万首，部分繁体需繁转简）
- 全宋诗 全唐诗/poet.song.*.json（约 26 万首）
- 宋词 宋词/ci.song.*.json（约 2.1 万首）

依赖：zhconv（pip install --user zhconv，繁转简；提取工具专用，
不进构建管线标准库约束）。

产出两类键值对（docs/一句诗联想整首诗调研与方案.md §3.2）：
1. 上一句 → 下一句（去标点，权重 90）：链式补全整首的主力链路；
2. 行内前缀 → 行内剩余（权重 50）：半句上屏后的续接。

句的切分：paragraph 按「，。；！？：、」切为句单元，单元依次成链
（兼容个别把两句并入一个 paragraph 的条目）。

用法：
    python3 scripts/extract_poetry_corpus.py \\
        --src dist/corpus_src/chinese-poetry \\
        --out dist/corpus_poetry_full.tsv \\
        [--curated dist/corpus_poetry_curated.tsv]

--curated 另产出名家精选子集（名家白名单 + 短诗，供进 APK 主库合并；
--curated-scope 限定精选范围，全量 --out 不受影响）。
"""

import argparse
import glob
import json
import os
import re
import sys

try:
    from zhconv import convert as t2s_convert
except ImportError:
    print("错误: 缺 zhconv，先执行 pip3 install --user zhconv", file=sys.stderr)
    sys.exit(1)

# 句内切分标点（切完再逐单元校验纯 CJK）
SPLIT_RE = re.compile(r"[，。；！？：、,\.!?()\[\]{}<>《》「」『』\s]+")
CJK_RANGES = ((0x4E00, 0x9FFF), (0x3400, 0x4DBF))

PAIR_WEIGHT = 90     # 上一句 → 下一句
PREFIX_WEIGHT = 50   # 行内前缀 → 剩余
MAX_KEY_CHARS = 8
MAX_CANDIDATE_CHARS = 20

# 名家精选白名单（curated 子集：唐宋高频名家，控体积进 APK）
FAMOUS_AUTHORS = {
    "李白", "杜甫", "白居易", "王维", "孟浩然", "王昌龄", "李商隐", "杜牧",
    "柳宗元", "刘禹锡", "韩愈", "贺知章", "张九龄", "王之涣", "岑参",
    "高适", "韦应物", "李贺", "李煜", "温庭筠", "韦庄", "王勃", "陈子昂",
    "苏轼", "辛弃疾", "李清照", "柳永", "欧阳修", "王安石", "陆游",
    "范仲淹", "岳飞", "晏殊", "晏几道", "秦观", "周邦彦", "姜夔",
    "文天祥", "杨万里", "朱熹", "苏辙", "黄庭坚", "李之仪", "张先",
}
CURATED_MAX_UNITS = 16  # 精选集默认只收短诗（绝句/律诗/小令），--curated-max-units 可调
CURATED_SCOPES = ("all", "tang", "ci")  # --curated-scope：名家范围（ci=仅宋词）


def is_cjk(s: str) -> bool:
    if not s:
        return False
    return all(any(lo <= ord(ch) <= hi for lo, hi in CJK_RANGES) for ch in s)


def to_units(paragraphs):
    """paragraphs → 句单元列表（繁转简、切标点、剔非 CJK、长度约束）。"""
    units = []
    for p in paragraphs:
        if not isinstance(p, str):
            continue
        p = t2s_convert(p, "zh-cn")
        for seg in SPLIT_RE.split(p):
            if 1 <= len(seg) <= MAX_CANDIDATE_CHARS and is_cjk(seg):
                units.append(seg)
    return units


def emit_poem(units, out, stats, curated_out=None, is_famous=False,
              curated_max_units=CURATED_MAX_UNITS):
    """输出一首诗的全部键值对；stats 计数。"""
    if len(units) < 2:
        return
    for i in range(len(units) - 1):
        key, cand = units[i], units[i + 1]
        if len(key) > MAX_KEY_CHARS:
            stats["skip_long_key"] += 1
            continue
        out.write(f"{key}\t{cand}\t{PAIR_WEIGHT}\n")
        stats["pairs"] += 1
    for u in units:
        # 行内前缀 → 剩余：前缀 2~min(7, len-1) 字
        for i in range(2, min(MAX_KEY_CHARS, len(u))):
            out.write(f"{u[:i]}\t{u[i:]}\t{PREFIX_WEIGHT}\n")
            stats["prefix_pairs"] += 1
    if curated_out is not None and is_famous and len(units) <= curated_max_units:
        for i in range(len(units) - 1):
            key, cand = units[i], units[i + 1]
            if len(key) > MAX_KEY_CHARS:
                continue
            curated_out.write(f"{key}\t{cand}\t{PAIR_WEIGHT}\n")
            stats["curated_pairs"] += 1


def iter_poems(src_dir):
    """遍历全部语料文件，yield (author, paragraphs, source)。"""
    tang = sorted(glob.glob(os.path.join(src_dir, "全唐诗", "poet.tang.*.json")))
    song = sorted(glob.glob(os.path.join(src_dir, "全唐诗", "poet.song.*.json")))
    ci_files = sorted(glob.glob(os.path.join(src_dir, "宋词", "ci.song.*.json")))
    for source, files in (("tang", tang), ("song", song), ("ci", ci_files)):
        for path in files:
            try:
                with open(path, "r", encoding="utf-8") as f:
                    data = json.load(f)
            except (json.JSONDecodeError, UnicodeDecodeError) as e:
                print(f"[warn] 跳过损坏文件 {path}: {e}", file=sys.stderr)
                continue
            for item in data:
                if not isinstance(item, dict):
                    continue
                paragraphs = item.get("paragraphs")
                if not paragraphs:
                    continue
                yield str(item.get("author", "")).strip(), paragraphs, source


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--src", required=True, help="chinese-poetry 仓库目录")
    ap.add_argument("--out", help="全量 TSV 输出（可省略：仅产出 --curated 时）")
    ap.add_argument("--curated", help="名家精选 TSV 输出（可选）")
    ap.add_argument("--curated-scope", choices=CURATED_SCOPES, default="all",
                    help="精选集语料范围：all=唐诗+宋诗+宋词，tang=仅全唐诗，ci=仅宋词")
    ap.add_argument("--curated-max-units", type=int, default=CURATED_MAX_UNITS,
                    help=f"精选集诗长上限（句数，默认 {CURATED_MAX_UNITS}；8=仅绝句/律诗）")
    args = ap.parse_args()
    if not args.out and not args.curated:
        print("错误: --out 与 --curated 至少提供一个", file=sys.stderr)
        return 1

    stats = {"poems": 0, "pairs": 0, "prefix_pairs": 0,
             "curated_pairs": 0, "skip_long_key": 0}
    out = open(args.out, "w", encoding="utf-8") if args.out else None
    if out:
        out.write("# 由 extract_poetry_corpus.py 生成；"
                  "源: chinese-poetry（MIT）\n")
    curated_out = None
    if args.curated:
        curated_out = open(args.curated, "w", encoding="utf-8")
        curated_out.write("# 由 extract_poetry_corpus.py 生成；"
                          "源: chinese-poetry（MIT）名家精选\n")
    try:
        for author, paragraphs, source in iter_poems(args.src):
            units = to_units(paragraphs)
            if len(units) < 2:
                continue
            stats["poems"] += 1
            in_scope = args.curated_scope == "all" or source == args.curated_scope
            is_famous = in_scope and \
                t2s_convert(author, "zh-cn") in FAMOUS_AUTHORS
            if out:
                emit_poem(units, out, stats, curated_out,
                          is_famous=is_famous,
                          curated_max_units=args.curated_max_units)
            elif curated_out and is_famous and \
                    len(units) <= args.curated_max_units:
                # 仅精选模式：直接写句对，不走全量分支
                for i in range(len(units) - 1):
                    key, cand = units[i], units[i + 1]
                    if len(key) <= MAX_KEY_CHARS:
                        curated_out.write(f"{key}\t{cand}\t{PAIR_WEIGHT}\n")
                        stats["curated_pairs"] += 1
    finally:
        if curated_out:
            curated_out.close()
        if out:
            out.close()

    if args.out:
        print(f"诗词 {stats['poems']} 首 → 句对 {stats['pairs']}，"
              f"行内前缀对 {stats['prefix_pairs']}"
              f"（跳过超长键 {stats['skip_long_key']}）→ {args.out}")
    if args.curated:
        print(f"名家精选句对: {stats['curated_pairs']} → {args.curated}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
