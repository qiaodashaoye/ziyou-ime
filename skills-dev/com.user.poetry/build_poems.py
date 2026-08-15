#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build_poems.py —— 诗词助手技能的内置数据生成器

从 chinese-poetry 数据集（MIT）提取名家短诗，产出 poems.js 供技能内置
离线检索（WebView 沙箱无 network 权限）。与候选栏联想（predict.db 句对）
互补：面板覆盖整首浏览/逐句发送/按题目作者检索。

用法：
    python3 skills-dev/com.user.poetry/build_poems.py \
        --src dist/corpus_src/chinese-poetry \
        --max-poems 1200 --max-lines 12

产出 poems.js：`var POEMS=[{t:题目,a:作者,l:[句,...]},...]`（去标点纯汉字句）。
依赖 zhconv（繁转简，与 scripts/extract_poetry_corpus.py 同口径）。
"""

import argparse
import glob
import json
import os
import re
import sys

from zhconv import convert as t2s

SPLIT_RE = re.compile(r"[，。；！？：、,\.!?()\[\]{}<>《》「」『』\s]+")
TITLE_RE = re.compile(r"[^\u4E00-\u9FFF\u3400-\u4DBF]")

FAMOUS_AUTHORS = {
    "李白", "杜甫", "白居易", "王维", "孟浩然", "王昌龄", "李商隐", "杜牧",
    "柳宗元", "刘禹锡", "韩愈", "贺知章", "张九龄", "王之涣", "岑参",
    "高适", "韦应物", "李贺", "李煜", "温庭筠", "韦庄", "王勃", "陈子昂",
    "苏轼", "辛弃疾", "李清照", "柳永", "欧阳修", "王安石", "陆游",
    "范仲淹", "岳飞", "晏殊", "晏几道", "秦观", "周邦彦", "姜夔",
    "文天祥", "杨万里", "朱熹", "苏辙", "黄庭坚", "李之仪", "张先",
}


def is_cjk(s):
    return bool(s) and all('\u4E00' <= c <= '\u9FFF' or '\u3400' <= c <= '\u4DBF'
                           for c in s)


def clean_title(title):
    """题目归一：繁转简、剔非汉字、截断（词牌名 rhythmic 同口径）。"""
    t = TITLE_RE.sub("", t2s(str(title), "zh-cn"))
    return t[:20]


def to_units(paragraphs):
    units = []
    for p in paragraphs:
        if not isinstance(p, str):
            continue
        for seg in SPLIT_RE.split(t2s(p, "zh-cn")):
            if 1 <= len(seg) <= 20 and is_cjk(seg):
                units.append(seg)
    return units


def iter_items(src_dir):
    """yield (author, title, paragraphs)，唐诗优先（精选排序倾向）。"""
    tang = sorted(glob.glob(os.path.join(src_dir, "全唐诗", "poet.tang.*.json")))
    song = sorted(glob.glob(os.path.join(src_dir, "全唐诗", "poet.song.*.json")))
    ci = sorted(glob.glob(os.path.join(src_dir, "宋词", "ci.song.*.json")))
    for files, title_key in ((tang, "title"), (song, "title"), (ci, "rhythmic")):
        for path in files:
            try:
                data = json.load(open(path, encoding="utf-8"))
            except (json.JSONDecodeError, UnicodeDecodeError):
                continue
            for item in data:
                if not isinstance(item, dict) or not item.get("paragraphs"):
                    continue
                yield (str(item.get("author", "")).strip(),
                       item.get(title_key, ""), item["paragraphs"])


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--src", required=True)
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "poems.js"))
    ap.add_argument("--max-poems", type=int, default=1200)
    ap.add_argument("--max-lines", type=int, default=12)
    args = ap.parse_args()

    poems, seen = [], set()
    for author, title, paragraphs in iter_items(args.src):
        if len(poems) >= args.max_poems:
            break
        a = t2s(author, "zh-cn")
        if a not in FAMOUS_AUTHORS:
            continue
        units = to_units(paragraphs)
        if not (2 <= len(units) <= args.max_lines):
            continue
        t = clean_title(title) or "无题"
        key = (a, t, units[0])
        if key in seen:  # 同作者同题同首句去重（数据集存在重出诗）
            continue
        seen.add(key)
        poems.append({"t": t, "a": a, "l": units})

    with open(args.out, "w", encoding="utf-8") as f:
        f.write("// 由 build_poems.py 生成；源: chinese-poetry（MIT）名家短诗精选\n")
        f.write("var POEMS = ")
        json.dump(poems, f, ensure_ascii=False, separators=(",", ":"))
        f.write(";\n")
    print(f"诗词 {len(poems)} 首 → {args.out}"
          f"（{os.path.getsize(args.out) / 1024:.0f} KB）")


if __name__ == "__main__":
    sys.exit(main())
