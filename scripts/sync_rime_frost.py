#!/usr/bin/env python3
# encoding: utf-8
"""白霜拼音(rime-frost)资源拆分脚本 —— 迁移方案 Phase 2(见 docs/白霜拼音词库迁移可行性方案.md)

职责:rime-frost 上游源 → 两条分发通道的单向同步/打包
  1. sync-core   核心词表同步进 ziyou-ime assets(8105/base/others/corrections +
                 en/en_ext + lua 精简集 + opencc emoji 表 + melt_eng/custom_phrase)。
                 带 --check 时仅比对不写入(CI 漂移检测用)。
  2. pack-dicts  可下载词库打包至 ziyou-ime-dicts 仓库 staging 区:
                 frost_ext / frost_tencent 单表直拷;四组细胞词库「并表合并」为
                 单一 dict.yaml(沿用现有 DictManager 下载管线:一个 id 一个文件);
                 同时输出 sha256/size 清单 JSON 供 catalog.json v4 回填。

设计约束:
  - id 命名符合 DictModels.RemoteDictInfo 白名单 ^[A-Za-z0-9_-]+$;
  - 落盘文件名统一 <id>.dict.yaml,与 DictManager 安装/卸载/预览路径一致;
  - 并表仅拼接词条区(首个源的 YAML 头 + 各源 '...' 之后正文),保留权重原值,
    重复词条由 rime 引擎按 import 叠加语义处理(与 frost 上游多表共挂行为一致)。

用法:
  python3 scripts/sync_rime_frost.py sync-core [--frost PATH] [--check]
  python3 scripts/sync_rime_frost.py pack-dicts [--frost PATH] [--out PATH]
"""

import argparse
import hashlib
import json
import shutil
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_FROST = REPO_ROOT.parent / "rime-frost-master"
ASSETS_RIME = REPO_ROOT / "app/src/main/assets/rime"
DEFAULT_DICTS_REPO = REPO_ROOT.parent / "ziyou-ime-dicts"

# ── sync-core 清单:frost 相对路径 → assets/rime 相对路径 ──────────────────
CORE_FILES = {
    # 历史备注:custom_phrase_t9.txt 曾为字由自建资产并于 2026-08 评估后移除
    # (31 条全部被白霜词表收录,固顶冗余),本清单无此条目。
    # 核心中文词表(覆盖式同步;8105/base 为白霜重统计字频/词频主体)
    "cn_dicts/8105.dict.yaml": "cn_dicts/8105.dict.yaml",
    "cn_dicts/base.dict.yaml": "cn_dicts/base.dict.yaml",
    "cn_dicts/others.dict.yaml": "cn_dicts/others.dict.yaml",
    "cn_dicts/corrections.dict.yaml": "cn_dicts/corrections.dict.yaml",
    # 英文主词库(melt_eng 次翻译器依赖;ziyou 自有 en_dicts/base 不覆盖)
    "en_dicts/en.dict.yaml": "en_dicts/en.dict.yaml",
    "en_dicts/en_ext.dict.yaml": "en_dicts/en_ext.dict.yaml",
    # 中英混输词表(table_translator@cn_en + cn_en_spacer,白霜补齐项)
    "en_dicts/cn_en.txt": "en_dicts/cn_en.txt",
    # melt_eng 方案与自定义短语
    "melt_eng.schema.yaml": "melt_eng.schema.yaml",
    "melt_eng.dict.yaml": "melt_eng.dict.yaml",
    "custom_phrase.txt": "custom_phrase.txt",
    # OpenCC emoji 功能表(json + txt 源表,与 ziyou 现有链路格式一致)
    "opencc/emoji.json": "opencc/emoji.json",
    "opencc/emoji.txt": "opencc/emoji.txt",
    "opencc/others.txt": "opencc/others.txt",
}

# Lua 精简集:rime_frost.schema.yaml(裁剪版)实际引用的脚本
LUA_SCRIPTS = [
    "select_character", "date_translator", "lunar", "unicode",
    "number_translator", "calculator", "calc_translator", "force_gc",
    "is_in_user_dict", "corrector", "autocap_filter", "v_filter",
    "pin_cand_filter", "reduce_english_filter", "cn_en_spacer",
]

# ── pack-dicts 打包定义 ─────────────────────────────────────────────────
# 单表直拷:frost 相对路径 → 目标 id
PACK_SINGLE = {
    "cn_dicts/ext.dict.yaml": "frost_ext",
    "cn_dicts/tencent.dict.yaml": "frost_tencent",
}
# 并表分组:目标 id → (frost 相对路径列表, 展示名)
# 分组依据:同域聚合 + gzip 后单包控制在 Gitee 匿名下载限额内
PACK_GROUPS = {
    "frost_cell_life": (
        [
            "cn_dicts_cell/medication.dict.yaml",
            "cn_dicts_cell/industry_product.dict.yaml",
            "cn_dicts_cell/computer.dict.yaml",
            "cn_dicts_cell/composite.dict.yaml",
            "cn_dicts_cell/exthot.dict.yaml",
            "cn_dicts_cell/inputmethod.dict.yaml",
            "cn_dicts_cell/luna.dict.yaml",
        ],
        "白霜细胞词库·生活与科技(医药/工业产品/计算机/综合/热词/输入法/朙月补充)",
    ),
    "frost_cell_culture": (
        [
            "cn_dicts_cell/chess.dict.yaml",
            "cn_dicts_cell/chess2.dict.yaml",
            "cn_dicts_cell/animal.dict.yaml",
            "cn_dicts_cell/game.dict.yaml",
            "cn_dicts_cell/sport.dict.yaml",
            "cn_dicts_cell/media.dict.yaml",
            "cn_dicts_cell/food.dict.yaml",
            "cn_dicts_cell/idiom.dict.yaml",
            "cn_dicts_cell/literature.dict.yaml",
            "cn_dicts_cell/music.dict.yaml",
            "cn_dicts_cell/shulihua.dict.yaml",
        ],
        "白霜细胞词库·文娱(棋牌/动物/游戏/体育/影视/美食/成语/文学/音乐/数理化学)",
    ),
    "frost_cell_geo": (
        [
            "cn_dicts_cell/history.dict.yaml",
            "cn_dicts_cell/place.dict.yaml",
            "cn_dicts_cell/geography.dict.yaml",
            "cn_dicts_cell/name.dict.yaml",
            "cn_dicts_cell/name2.dict.yaml",
        ],
        "白霜细胞词库·地理与人名(历史/地名/地理/人名)",
    ),
    "frost_bigchars": (
        [
            "cn_dicts/41448.dict.yaml",
            "cn_dicts/GB18030-2022.dict.yaml",
        ],
        "白霜大字表(41448 字 + GB18030-2022,生僻字需求按需安装)",
    ),
}


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _split_dict(content: str):
    """拆分 dict.yaml 为 (YAML 头文本, 词条行列表);无 '...' 分隔视为全词条。"""
    lines = content.splitlines()
    for i, line in enumerate(lines):
        if line.strip() == "...":
            return "\n".join(lines[: i + 1]), lines[i + 1:]
    return "", lines


def _merge_dicts(frost: Path, sources: list, banner: str) -> str:
    """并表合并:首个源的 YAML 头 + 各源词条区拼接,头部注明组成。"""
    header, _ = _split_dict((frost / sources[0]).read_text(encoding="utf-8"))
    parts = [
        "# 白霜拼音细胞词库并表包(由 scripts/sync_rime_frost.py 生成,勿手改)",
        f"# {banner}",
        "# 组成:",
    ]
    parts += [f"#   - {s}" for s in sources]
    parts.append("")
    parts.append(header)
    for src in sources:
        _, entries = _split_dict((frost / src).read_text(encoding="utf-8"))
        parts.append("")
        parts.append(f"# ===== 以下来自 {src} =====")
        parts.extend(entries)
    return "\n".join(parts) + "\n"


def cmd_sync_core(frost: Path, check: bool) -> int:
    if not frost.is_dir():
        print(f"错误:frost 源目录不存在: {frost}", file=sys.stderr)
        return 2
    drift = []
    for rel_src, rel_dst in CORE_FILES.items():
        src = frost / rel_src
        dst = ASSETS_RIME / rel_dst
        if not src.exists():
            print(f"错误:frost 源缺失 {rel_src}", file=sys.stderr)
            return 2
        if check:
            if not dst.exists() or dst.read_bytes() != src.read_bytes():
                drift.append(rel_dst)
        else:
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(src, dst)
    for name in LUA_SCRIPTS:
        src = frost / "lua" / f"{name}.lua"
        dst = ASSETS_RIME / "lua" / f"{name}.lua"
        if not src.exists():
            print(f"错误:frost lua 脚本缺失 {name}.lua", file=sys.stderr)
            return 2
        if check:
            if not dst.exists() or dst.read_bytes() != src.read_bytes():
                drift.append(f"lua/{name}.lua")
        else:
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(src, dst)

    total = len(CORE_FILES) + len(LUA_SCRIPTS)
    if check:
        if drift:
            print(f"漂移检测:assets 与 frost 源不一致({len(drift)}/{total}):")
            for d in drift:
                print(f"  - {d}")
            return 1
        print(f"漂移检测通过:assets 与 frost 源一致({total} 个文件)")
        return 0
    print(f"sync-core 完成:同步 {total} 个文件至 {ASSETS_RIME}")
    return 0


def cmd_pack_dicts(frost: Path, out: Path) -> int:
    if not frost.is_dir():
        print(f"错误:frost 源目录不存在: {frost}", file=sys.stderr)
        return 2
    out.mkdir(parents=True, exist_ok=True)
    manifest = []

    def emit(dict_id: str, content: bytes, name: str, note: str):
        path = out / f"{dict_id}.dict.yaml"
        path.write_bytes(content)
        manifest.append({
            "id": dict_id,
            "name": name,
            "file": path.name,
            "size": len(content),
            "sha256": _sha256(path),
            "note": note,
        })

    for rel, dict_id in PACK_SINGLE.items():
        src = frost / rel
        emit(dict_id, src.read_bytes(),
             f"白霜扩展词库 {dict_id.removeprefix('frost_')}",
             f"单表直拷自 {rel}")

    for dict_id, (sources, name) in PACK_GROUPS.items():
        for s in sources:
            if not (frost / s).exists():
                print(f"错误:frost 源缺失 {s}", file=sys.stderr)
                return 2
        content = _merge_dicts(frost, sources, name).encode("utf-8")
        emit(dict_id, content, name, f"并表合并 {len(sources)} 个源表")

    manifest_path = out / "frost_pack_manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"pack-dicts 完成:{len(manifest)} 个包 → {out}")
    for m in manifest:
        print(f"  {m['id']:<20} {m['size']/1048576:6.1f}MB  {m['sha256'][:16]}…")
    print(f"清单(供 catalog v4 回填):{manifest_path}")
    return 0


def main():
    parser = argparse.ArgumentParser(description="rime-frost 资源拆分脚本")
    parser.add_argument("command", choices=["sync-core", "pack-dicts"])
    parser.add_argument("--frost", type=Path, default=DEFAULT_FROST,
                        help="rime-frost 源目录(默认 %(default)s)")
    parser.add_argument("--check", action="store_true",
                        help="sync-core 仅比对不写入(CI 漂移检测)")
    parser.add_argument("--out", type=Path,
                        default=DEFAULT_DICTS_REPO / "dist" / "frost",
                        help="pack-dicts 输出目录(默认 %(default)s)")
    args = parser.parse_args()
    if args.command == "sync-core":
        return cmd_sync_core(args.frost, args.check)
    return cmd_pack_dicts(args.frost, args.out)


if __name__ == "__main__":
    sys.exit(main())
