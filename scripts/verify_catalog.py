#!/usr/bin/env python3
# encoding: utf-8
"""catalog.json 供应链安全校验脚本 —— 迁移方案 5.3(CI 门禁)

校验 ziyou-ime-dicts/catalog.json 的完整性与安全性:
  1. JSON 结构与 version 单调性
  2. 每个条目 id 过客户端同款白名单 ^[A-Za-z0-9_-]+$(防路径穿越)
  3. url 强制 HTTPS + 域名白名单(gitee.com 项目仓库域)
  4. sha256 强制非空(v4 起)且为合法 64 位十六进制
  5. deprecated_by 非空时过 id 白名单
  6. --local-dir 提供打包产物目录时,对 file 存在的条目复算 sha256/size
     与 catalog 比对(防清单投毒/产物漂移)
  7. --network 时 HEAD 请求校验 url 可达(默认关闭:CI 离线可用,
     frost 系列 Release 上传前不阻塞)

用法:
  python3 scripts/verify_catalog.py                       # 离线全量校验
  python3 scripts/verify_catalog.py --local-dir ../ziyou-ime-dicts/dist/frost
  python3 scripts/verify_catalog.py --network             # 附加 url 可达性
"""

import argparse
import hashlib
import json
import re
import sys
import urllib.request
from pathlib import Path
from typing import Optional

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_CATALOG = REPO_ROOT.parent / "ziyou-ime-dicts" / "catalog.json"

# 与客户端 RemoteDictInfo.isValidId 保持一致
ID_PATTERN = re.compile(r"^[A-Za-z0-9_-]+$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
# 与客户端 requireTrustedUrl 的域名白名单语义对齐
TRUSTED_HOST_SUFFIXES = ("gitee.com",)
MIN_CATALOG_VERSION = 4


def _fail(errors: list, msg: str):
    errors.append(msg)
    print(f"  ✗ {msg}")


def _ok(msg: str):
    print(f"  ✓ {msg}")


def verify(catalog_path: Path, local_dir: Optional[Path], network: bool) -> int:
    errors: list = []
    print(f"校验 catalog: {catalog_path}")
    try:
        root = json.loads(catalog_path.read_text(encoding="utf-8"))
    except Exception as e:
        print(f"  ✗ JSON 解析失败: {e}")
        return 1

    version = root.get("version", 0)
    if version < MIN_CATALOG_VERSION:
        _fail(errors, f"version={version} 低于 v{MIN_CATALOG_VERSION}(sha256 强制策略自 v4 起)")

    dicts = root.get("dictionaries", [])
    if not dicts:
        _fail(errors, "dictionaries 为空")

    seen_ids = set()
    for d in dicts:
        did = d.get("id", "")
        label = f"[{did or '<无id>'}]"
        if not ID_PATTERN.match(did):
            _fail(errors, f"{label} id 不符合白名单 ^[A-Za-z0-9_-]+$")
            continue
        if did in seen_ids:
            _fail(errors, f"{label} id 重复")
        seen_ids.add(did)

        url = d.get("url", "")
        if not url.startswith("https://"):
            _fail(errors, f"{label} url 非 HTTPS: {url}")
        else:
            host = url[len("https://"):].split("/", 1)[0].lower()
            if not any(host == s or host.endswith("." + s) for s in TRUSTED_HOST_SUFFIXES):
                _fail(errors, f"{label} url 域名不在白名单: {host}")

        sha = d.get("sha256", "")
        if not sha:
            _fail(errors, f"{label} 缺少 sha256(v4 起强制)")
        elif not SHA256_PATTERN.match(sha):
            _fail(errors, f"{label} sha256 非法: {sha}")

        dep = d.get("deprecated_by", "")
        if dep and not ID_PATTERN.match(dep):
            _fail(errors, f"{label} deprecated_by 非法: {dep}")

        if d.get("size", 0) <= 0:
            _fail(errors, f"{label} size 非正数")

        # 本地产物复算(frost 打包包)
        if local_dir is not None:
            local_file = local_dir / f"{did}.dict.yaml"
            if local_file.exists():
                h = hashlib.sha256()
                with local_file.open("rb") as f:
                    for chunk in iter(lambda: f.read(1 << 20), b""):
                        h.update(chunk)
                actual_sha = h.hexdigest()
                actual_size = local_file.stat().st_size
                if actual_sha != sha:
                    _fail(errors, f"{label} 本地文件 sha256 与 catalog 不一致")
                elif actual_size != d.get("size"):
                    _fail(errors, f"{label} 本地文件 size({actual_size}) 与 catalog({d.get('size')}) 不一致")
                else:
                    _ok(f"{label} 本地产物 sha256/size 一致")

        # url 可达性(可选)
        if network:
            try:
                req = urllib.request.Request(url, method="HEAD")
                with urllib.request.urlopen(req, timeout=15) as resp:
                    if resp.status == 200:
                        _ok(f"{label} url 可达")
                    else:
                        _fail(errors, f"{label} url 状态码 {resp.status}")
            except Exception as e:
                _fail(errors, f"{label} url 不可达: {e}")

    if errors:
        print(f"\n校验失败:{len(errors)} 处问题")
        return 1
    print(f"\n校验通过:{len(dicts)} 个条目全部合规")
    return 0


def main():
    parser = argparse.ArgumentParser(description="catalog.json 供应链安全校验")
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--local-dir", type=Path, default=None,
                        help="打包产物目录(存在 <id>.dict.yaml 时复算 sha256/size)")
    parser.add_argument("--network", action="store_true", help="附加 HEAD 请求校验 url 可达")
    args = parser.parse_args()
    return verify(args.catalog, args.local_dir, args.network)


if __name__ == "__main__":
    sys.exit(main())
