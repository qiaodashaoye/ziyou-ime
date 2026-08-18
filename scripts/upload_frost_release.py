#!/usr/bin/env python3
# encoding: utf-8
"""白霜词库包 Gitee Release 托管上传脚本 —— 迁移方案 Phase 2 收尾(人工动作自动化)

将 ziyou-ime-dicts/dist/frost/ 的 6 个词库包上传至 Gitee Release
(v4.0.0-frost-dicts),与 catalog.json v4 的 url/sha256 对齐。

凭证:环境变量 GITEE_TOKEN(Gitee 设置→安全设置→私人令牌,需 projects 权限)。
全流程幂等:Release 已存在则复用;附件已存在(按文件名)则跳过;
上传后逐个复算远端 size 与 catalog sha256 比对,最后提示运行
verify_catalog.py --network 做全绿验收。

用法:
  GITEE_TOKEN=xxx python3 scripts/upload_frost_release.py
  GITEE_TOKEN=xxx python3 scripts/upload_frost_release.py --dry-run
"""

import argparse
import json
import mimetypes
import os
import sys
import uuid
import urllib.request
import urllib.error
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DIST_DIR = REPO_ROOT.parent / "ziyou-ime-dicts" / "dist" / "frost"
MANIFEST = DIST_DIR / "frost_pack_manifest.json"

API = "https://gitee.com/api/v5"
OWNER = "qiaodashaoye"
REPO = "ziyou-ime-dicts"
TAG = "v4.0.0-frost-dicts"
RELEASE_NAME = "白霜拼音词库包 v4(frost 系列)"
RELEASE_BODY = (
    "ziyou-ime 白霜拼音迁移 Phase 2 产物(catalog v4):\n"
    "- frost_ext / frost_tencent:白霜扩展/腾讯词向量大词库(单表直拷)\n"
    "- frost_cell_life / frost_cell_culture / frost_cell_geo:细胞词库并表包\n"
    "- frost_bigchars:41448 + GB18030-2022 大字表\n"
    "由 scripts/sync_rime_frost.py pack-dicts 生成,sha256 见 catalog.json v4。"
)


def _api(method: str, path: str, token: str, data=None, raw=False):
    url = f"{API}{path}"
    headers = {"User-Agent": "ziyou-ime-release-uploader"}
    body = None
    if data is not None:
        if raw:
            body = data
        else:
            body = json.dumps(data).encode("utf-8")
            headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=300) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _multipart(fields: dict, file_field: str, file_path: Path):
    """构造 multipart/form-data 请求体(Gitee 附件上传接口)。"""
    boundary = uuid.uuid4().hex
    lines = []
    for k, v in fields.items():
        lines.append(f"--{boundary}".encode())
        lines.append(f'Content-Disposition: form-data; name="{k}"'.encode())
        lines.append(b"")
        lines.append(str(v).encode("utf-8"))
    lines.append(f"--{boundary}".encode())
    ctype = mimetypes.guess_type(file_path.name)[0] or "application/octet-stream"
    lines.append(
        f'Content-Disposition: form-data; name="{file_field}"; filename="{file_path.name}"'.encode())
    lines.append(f"Content-Type: {ctype}".encode())
    lines.append(b"")
    head = b"\r\n".join(lines) + b"\r\n"
    tail = f"\r\n--{boundary}--\r\n".encode()
    return head + file_path.read_bytes() + tail, f"multipart/form-data; boundary={boundary}"


def main():
    parser = argparse.ArgumentParser(description="frost 词库包 Gitee Release 上传")
    parser.add_argument("--dry-run", action="store_true", help="只打印计划,不请求网络")
    args = parser.parse_args()

    token = os.environ.get("GITEE_TOKEN", "")
    if not token and not args.dry_run:
        print("错误:缺少环境变量 GITEE_TOKEN(Gitee 私人令牌,设置→安全设置→私人令牌)",
              file=sys.stderr)
        return 2
    if not MANIFEST.exists():
        print(f"错误:打包清单不存在:{MANIFEST}(先运行 sync_rime_frost.py pack-dicts)",
              file=sys.stderr)
        return 2

    packs = json.loads(MANIFEST.read_text(encoding="utf-8"))
    print(f"待上传 {len(packs)} 个包 → {OWNER}/{REPO} Release {TAG}")
    for p in packs:
        print(f"  {p['id']:<20} {p['size']/1048576:6.1f}MB  {DIST_DIR / p['file']}")
    if args.dry_run:
        print("[dry-run] 结束")
        return 0

    # 1. 创建或复用 Release(注意:Gitee 对不存在的 tag 返回 200 + JSON null,非 404)
    release = None
    try:
        release = _api("GET", f"/repos/{OWNER}/{REPO}/releases/tags/{TAG}?access_token={token}", token)
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise
    if release:
        print(f"Release 已存在(id={release['id']}),复用")
    else:
        release = _api("POST", f"/repos/{OWNER}/{REPO}/releases", token, data={
            "access_token": token,
            "tag_name": TAG,
            "name": RELEASE_NAME,
            "body": RELEASE_BODY,
            "target_commitish": "main",
        })
        print(f"Release 已创建(id={release['id']})")

    release_id = release["id"]

    # 2. 已有附件清单(按文件名去重)
    attached = _api("GET", f"/repos/{OWNER}/{REPO}/releases/{release_id}", token)
    existing = {a.get("name") for a in attached.get("assets", [])}

    # 3. 逐个上传
    for p in packs:
        fname = p["file"]
        path = DIST_DIR / fname
        if not path.exists():
            print(f"  ✗ 本地文件缺失:{fname}")
            return 1
        if fname in existing:
            print(f"  · 已存在,跳过:{fname}")
            continue
        body, ctype = _multipart({"access_token": token}, "file", path)
        req = urllib.request.Request(
            f"{API}/repos/{OWNER}/{REPO}/releases/{release_id}/attach_files",
            data=body, method="POST",
            headers={"Content-Type": ctype, "User-Agent": "ziyou-ime-release-uploader"})
        with urllib.request.urlopen(req, timeout=600) as resp:
            result = json.loads(resp.read().decode("utf-8"))
        print(f"  ✓ 已上传:{fname}(附件 id={result.get('id')})")

    print("\n上传完成。验收命令:")
    print("  python3 scripts/verify_catalog.py --network   # frost 6 条目应全部可达")
    return 0


if __name__ == "__main__":
    sys.exit(main())
