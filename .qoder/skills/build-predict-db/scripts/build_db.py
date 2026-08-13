#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""build-predict-db Skill 编排脚本（构建 / 验收 / 环境检查）。

定位：自建 predict.db **完全替代**官方 data-1.0 的构建-验收编排层。
对仓库 scripts/build_predict_db.py 的薄封装 + 召回率 A/B 自动化：
- --check   环境检查（工具探测 + 基线/探针就位判定），不构建
- 默认      执行构建（透传语料/过滤/权重参数），顺带导出合并 TSV 供质检
- --verify  双层召回率验收（基线恒为 scripts/predict_official_baseline.db
            官方副本，不随 assets/predict.db 被替换而漂移）：
            ① 首次替换红线：候选（+后缀回退）相对官方基线提升 ≥50%
            ② 迭代回归门禁（--prev-db）：相对上一版召回不降（容忍 -2%）
            ③ 长尾门禁（--probe-long）：长尾探针召回不显著低于官方（容忍 -10%）

仅 Python 3.8+ 标准库。退出码：0 成功（验收模式=全部门禁达标）；非 0 失败或未达标。
"""

import argparse
import os
import re
import subprocess
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
# .qoder/skills/build-predict-db/scripts/ → 仓库根
REPO_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, "..", "..", "..", ".."))

BUILD_PIPELINE = os.path.join(REPO_ROOT, "scripts", "build_predict_db.py")
# 官方基线永久副本（见 scripts/predict_baseline_manifest.txt 纪律）
OFFICIAL_BASELINE = os.path.join(REPO_ROOT, "scripts", "predict_official_baseline.db")
# 副本缺失时的降级基线（警告：assets 可能已被自建库替换，基线语义漂移）
ASSETS_DB = os.path.join(REPO_ROOT, "app", "src", "main", "assets", "predict.db")
DEFAULT_PROBE = os.path.join(REPO_ROOT, "scripts", "predict_probe_words.txt")

# 工具探测候选路径（与构建管线默认查找列表同源，另加 query_predict）
TOOL_CANDIDATES = [
    os.path.join(REPO_ROOT, "librime-prebuilt", "librime", "build", "bin", "Release"),
    os.path.join(REPO_ROOT, "librime-prebuilt", "librime", "build", "bin"),
    os.path.join(REPO_ROOT, "librime-prebuilt", "build", "bin"),
]

# 验收红线（三层门禁）
RED_LINE_FIRST = 50.0        # 首次替换：相对官方基线的提升下限（%）
REGRESSION_TOLERANCE = -2.0  # 迭代回归：相对上一版的容忍下限（%）
LONGTAIL_TOLERANCE = -10.0   # 长尾门禁：相对官方基线的容忍下限（%）


def find_tool(name, explicit=None):
    """探测工具二进制：显式指定 > 预置候选目录 > PATH。"""
    if explicit:
        return explicit if os.access(explicit, os.X_OK) else None
    for d in TOOL_CANDIDATES:
        cand = os.path.join(d, name)
        if os.access(cand, os.X_OK):
            return cand
    from shutil import which
    return which(name)


def baseline_db():
    """返回验收基线路径与是否官方副本（缺失时降级 assets 并告警）。"""
    if os.path.isfile(OFFICIAL_BASELINE):
        return OFFICIAL_BASELINE, True
    print(f"[warn] 官方基线副本缺失: {OFFICIAL_BASELINE}", file=sys.stderr)
    print("[warn] 降级使用 assets/predict.db 作基线——若其已被自建库替换，"
          "验收语义漂移！请从 git 历史恢复副本。", file=sys.stderr)
    return ASSETS_DB, False


def check_environment():
    """环境检查：返回 (是否可构建, 是否可验收)。"""
    print(f"[check] Python {sys.version.split()[0]}（要求 3.8+）")
    ok_py = sys.version_info >= (3, 8)
    ok_build_pipe = os.path.isfile(BUILD_PIPELINE)
    print(f"[check] 构建管线 scripts/build_predict_db.py: {'就位' if ok_build_pipe else '缺失!'}")

    build_tool = find_tool("build_predict")
    print(f"[check] build_predict: {build_tool or '缺失（make deps && make 构建宿主工具链）'}")
    query_tool = find_tool("query_predict")
    print(f"[check] query_predict: {query_tool or '缺失（同上，验收需要）'}")

    base, official = baseline_db()
    ok_baseline = os.path.isfile(base)
    label = "官方基线副本" if official else "降级 assets 基线"
    print(f"[check] {label}: {'就位' if ok_baseline else '缺失!'}（{base}）")
    ok_probe = os.path.isfile(DEFAULT_PROBE)
    print(f"[check] 探针词表: {'就位' if ok_probe else '缺失!'}")

    can_build = ok_py and ok_build_pipe and bool(build_tool)
    can_verify = can_build and bool(query_tool) and ok_baseline and ok_probe
    return can_build, can_verify, build_tool, query_tool


def run_build(args, build_tool):
    """执行构建（透传语料/过滤/权重参数），返回输出 db 路径。"""
    out = os.path.abspath(args.out)
    cmd = [sys.executable, BUILD_PIPELINE, "--out", out,
           "--tsv-out", out + ".merged.tsv", "--probe", args.probe]
    for corpus in args.corpus:
        cmd += ["--corpus", corpus]
    if args.adoptions:
        cmd += ["--adoptions", args.adoptions]
    if args.distill:
        cmd += ["--distill", "--distill-limit", str(args.distill_limit)]
    if args.no_seed:
        cmd.append("--no-seed")
    if args.no_tail_keys:
        cmd.append("--no-tail-keys")
    if args.blocklist:
        cmd += ["--blocklist", args.blocklist]
    if args.exclude:
        cmd += ["--exclude", args.exclude]
    for flag, value in [("--default-weight", args.default_weight),
                        ("--adoption-weight", args.adoption_weight),
                        ("--distill-weight", args.distill_weight),
                        ("--tail-decay", args.tail_decay)]:
        if value is not None:
            cmd += [flag, str(value)]
    if build_tool:
        cmd += ["--tool", build_tool]
    print(f"[build] {' '.join(cmd)}")
    rc = subprocess.call(cmd, cwd=REPO_ROOT)
    if rc != 0:
        print(f"[build] 构建失败（退出码 {rc}）", file=sys.stderr)
        sys.exit(rc)
    return out


def query_rate(query_tool, db_path, probe, fallback):
    """跑一次 query_predict，解析 rate= 百分比；失败返回 None。"""
    cmd = [query_tool, db_path] + (["--fallback"] if fallback else [])
    with open(probe, "rb") as f:
        proc = subprocess.run(cmd, stdin=f, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT)
    out = proc.stdout.decode("utf-8", errors="replace")
    if proc.returncode != 0:
        print(out, file=sys.stderr)
        return None
    m = re.search(r"rate=([\d.]+)%", out)
    if not m:
        print(f"[verify] 无法解析输出: {out}", file=sys.stderr)
        return None
    print(f"  {out.strip()}")
    return float(m.group(1))


def gain_pct(rate, base):
    """相对提升百分比（基线为 0 时无法计算，返回 None）。"""
    if base is None or rate is None or base <= 0:
        return None
    return (rate - base) / base * 100.0


def run_verify(args, db_path, query_tool):
    """双层召回率验收。返回是否全部门禁达标。"""
    base_db, official = baseline_db()
    tag = "官方基线副本" if official else "降级 assets 基线"
    passed = True

    # ===== 门禁 ①：首次替换红线（候选 B 相对官方基线 ≥ +50%）=====
    print(f"[verify-① 首次替换红线] {tag}: {base_db}")
    base = query_rate(query_tool, base_db, args.probe, fallback=False)
    print(f"[verify-①] 候选 A（纯精确）: {db_path}")
    exact = query_rate(query_tool, db_path, args.probe, fallback=False)
    print(f"[verify-①] 候选 B（+后缀回退）: {db_path}")
    with_fb = query_rate(query_tool, db_path, args.probe, fallback=True)
    g_a, g_b = gain_pct(exact, base), gain_pct(with_fb, base)
    if g_b is None:
        print("[verify-①] 验收执行失败", file=sys.stderr)
        return False
    print(f"[verify-①] 相对提升: 候选 A {g_a:+.1f}%，候选 B {g_b:+.1f}%"
          f"（红线 ≥{RED_LINE_FIRST:.0f}%）")
    ok1 = g_b >= RED_LINE_FIRST
    print(f"[verify-①] 判定: {'达标' if ok1 else '未达标，补语料/蒸馏后重走构建与验收'}")
    passed &= ok1

    # ===== 门禁 ②：迭代回归（候选 B 相对上一版 ≥ -2%）=====
    if args.prev_db:
        prev = os.path.abspath(args.prev_db)
        print(f"[verify-② 迭代回归] 上一版: {prev}")
        prev_fb = query_rate(query_tool, prev, args.probe, fallback=True)
        g_r = gain_pct(with_fb, prev_fb)
        if g_r is None:
            print("[verify-②] 验收执行失败", file=sys.stderr)
            return False
        print(f"[verify-②] 候选 B 相对上一版 {g_r:+.1f}%（容忍 ≥{REGRESSION_TOLERANCE:.0f}%）")
        ok2 = g_r >= REGRESSION_TOLERANCE
        print(f"[verify-②] 判定: {'达标' if ok2 else '未达标，存在召回回归，排查语料变更'}")
        passed &= ok2

    # ===== 门禁 ③：长尾探针（候选 B 相对官方基线 ≥ -10%）=====
    if args.probe_long:
        if not os.path.isfile(args.probe_long):
            print(f"[verify-③] 长尾探针不存在: {args.probe_long}", file=sys.stderr)
            return False
        print(f"[verify-③ 长尾门禁] 探针: {args.probe_long}")
        base_l = query_rate(query_tool, base_db, args.probe_long, fallback=False)
        cand_l = query_rate(query_tool, db_path, args.probe_long, fallback=True)
        g_l = gain_pct(cand_l, base_l)
        if g_l is None:
            print("[verify-③] 验收执行失败", file=sys.stderr)
            return False
        print(f"[verify-③] 候选 B 长尾相对基线 {g_l:+.1f}%（容忍 ≥{LONGTAIL_TOLERANCE:.0f}%）")
        ok3 = g_l >= LONGTAIL_TOLERANCE
        print(f"[verify-③] 判定: {'达标' if ok3 else '未达标，长尾召回塌方，扩充长尾语料'}")
        passed &= ok3
    else:
        print("[verify-③] 未提供 --probe-long：长尾门禁跳过——"
              "完全替代官方库前必须补跑（高频探针不足以证明长尾不塌）")

    print(f"[verify] 总结论: {'全部门禁达标，可进入部署' if passed else '存在未达标门禁'}")
    return passed


def print_deploy_hint(db_path):
    print("\n验收通过后的部署步骤（纯文件级替换，schema/引擎代码零改动）：")
    print(f"  cp {os.path.relpath(db_path, REPO_ROOT)} app/src/main/assets/predict.db")
    print("  # app/build.gradle.kts 的 versionCode +1（AssetDeployer 以版本变化触发重部署）")
    print("  # 提交信息记录：产物 sha256 + 语料源 commit（db 版本溯源）")
    print("  ./gradlew :app:assembleDebug")


def main():
    parser = argparse.ArgumentParser(description="build-predict-db Skill 编排脚本")
    parser.add_argument("--check", action="store_true", help="仅环境检查")
    parser.add_argument("--corpus", action="append", default=[], metavar="TSV")
    parser.add_argument("--adoptions", metavar="JSON")
    parser.add_argument("--distill", action="store_true")
    parser.add_argument("--distill-limit", type=int, default=200)
    parser.add_argument("--no-seed", action="store_true")
    parser.add_argument("--no-tail-keys", action="store_true")
    parser.add_argument("--blocklist", metavar="FILE", help="内容安全词表（透传）")
    parser.add_argument("--exclude", metavar="FILE", help="排除词表（透传）")
    parser.add_argument("--default-weight", type=float, help="语料默认权重（透传）")
    parser.add_argument("--adoption-weight", type=float, help="采纳满分权重（透传）")
    parser.add_argument("--distill-weight", type=float, help="蒸馏权重（透传）")
    parser.add_argument("--tail-decay", type=float, help="尾键衰减系数（透传）")
    parser.add_argument("--out", default=os.path.join(REPO_ROOT, "dist", "predict.db"))
    parser.add_argument("--tool", help="build_predict 二进制路径")
    parser.add_argument("--verify", action="store_true", help="构建后执行双层召回率验收")
    parser.add_argument("--db", help="--verify 单独使用时的候选 db 路径")
    parser.add_argument("--prev-db", help="迭代回归门禁的上一版 db（门禁②）")
    parser.add_argument("--probe-long", help="长尾探针词表（门禁③，完全替代前必跑）")
    parser.add_argument("--probe", default=DEFAULT_PROBE)
    args = parser.parse_args()

    can_build, can_verify, build_tool, query_tool = check_environment()
    if args.check:
        sys.exit(0 if can_verify else 1)

    if args.verify and args.db:
        # 纯验收模式：跳过构建
        if not can_verify:
            sys.exit(1)
        sys.exit(0 if run_verify(args, os.path.abspath(args.db), query_tool) else 1)

    if not can_build:
        print("[error] 构建条件不满足，先按上文提示补齐工具链", file=sys.stderr)
        sys.exit(1)
    db_path = run_build(args, build_tool)

    if args.verify:
        if not can_verify:
            print("[error] 验收条件不满足（缺 query_predict/基线/探针）", file=sys.stderr)
            sys.exit(1)
        if not run_verify(args, db_path, query_tool):
            sys.exit(1)
        print_deploy_hint(db_path)
    else:
        print(f"\n[done] 产物: {db_path}（质检 TSV: {db_path}.merged.tsv）")
        print("[next] 部署前务必执行验收: 重跑本脚本加 --verify")


if __name__ == "__main__":
    main()
