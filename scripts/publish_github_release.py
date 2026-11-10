#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
FilmCam / Relic Cam —— GitHub Release 资产发布与漂移检查。

每次升 versionCode 并出包归档后，必须跑一次本脚本：

    python scripts/publish_github_release.py --check     # 只读，确认是否落后
    python scripts/publish_github_release.py             # 发布当前版本
    python scripts/publish_github_release.py --dry-run   # 只看计划，不发请求

约定：
  · 版本号从 app/build.gradle.kts 读取（versionCode / versionName / minSdk）。
  · 资产固定取 play_console_upload/RelicCam-<versionName>-vc<versionCode>.{apk,aab}；
    文件名不匹配时按 <任意名>-vc<versionCode>.<ext> 兜底搜索。
  · 每个 versionCode 一个 Release：tag = v<versionName>-vc<versionCode>，
    make_latest=true，App 的「检查更新」跳 https://github.com/steveJJJ/FilmCam/releases
    就会把最新版排在第一个。
  · 幂等：同 tag 已存在则复用；同名资产 sha256 一致则跳过，否则删除重传。
  · 旧 Release 只在正文开头加「已废弃」横幅，不删资产、不改 tag、不动无资产的
    内测引导 Release（RelicCam），保证操作可逆。

凭据：优先 GH_TOKEN 环境变量，否则 `git credential fill`（Windows 凭据管理器）。
代理：读 https_proxy / http_proxy；都没设时用项目固定代理 http://127.0.0.1:7897，
可用 --no-proxy 关闭。仅用 Python 标准库，无第三方依赖。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

OWNER_REPO = "steveJJJ/FilmCam"
API = f"https://api.github.com/repos/{OWNER_REPO}"
UPLOAD = f"https://uploads.github.com/repos/{OWNER_REPO}"
RELEASES_PAGE = f"https://github.com/{OWNER_REPO}/releases"
DEFAULT_PROXY = "http://127.0.0.1:7897"
ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "play_console_upload"
GRADLE = ROOT / "app" / "build.gradle.kts"
DEPRECATED_MARK = "此版本已废弃"


# --------------------------------------------------------------------------- 基础


def log(msg: str = "") -> None:
    print(msg, flush=True)


def read_version() -> tuple[int, str, int | None]:
    s = GRADLE.read_text(encoding="utf-8")
    m = re.search(r"versionCode\s*=\s*(\d+)", s)
    if not m:
        raise RuntimeError("app/build.gradle.kts 里找不到 versionCode")
    vc = int(m.group(1))
    m = re.search(r'versionName\s*=\s*"([^"]+)"', s)
    vn = m.group(1) if m else "1.0.0"
    m = re.search(r"minSdk\s*=\s*(\d+)", s)
    min_sdk = int(m.group(1)) if m else None
    return vc, vn, min_sdk


def find_assets(vc: int, vn: str) -> dict[str, Path]:
    result: dict[str, Path] = {}
    for ext in ("apk", "aab"):
        exact = OUT_DIR / f"RelicCam-{vn}-vc{vc}.{ext}"
        candidates = [exact] if exact.exists() else sorted(
            OUT_DIR.glob(f"*-vc{vc}.{ext}")
        )
        if not candidates:
            raise RuntimeError(
                f"找不到 vc{vc} 的 .{ext} 归档包，请先构建并归档到 {OUT_DIR}"
            )
        p = candidates[0]
        if p.stat().st_size == 0:
            raise RuntimeError(f"归档包为空：{p}")
        result[ext] = p
    return result


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def get_token() -> str:
    t = os.environ.get("GH_TOKEN", "").strip()
    if t:
        return t
    p = subprocess.run(
        ["git", "credential", "fill"],
        input="protocol=https\nhost=github.com\n\n",
        capture_output=True,
        text=True,
        cwd=ROOT,
    )
    for line in p.stdout.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1].strip()
    raise RuntimeError(
        "拿不到 GitHub token：请设置 GH_TOKEN，或确认 git credential helper 已配置"
    )


_OPENER = None


def opener():
    """显式构造带代理的 opener，不依赖环境变量的自动探测（曾遇到瞬时 502）。"""
    global _OPENER
    if _OPENER is None:
        handlers = []
        if not os.environ.get("FILMCAM_NO_PROXY"):
            proxy = (
                os.environ.get("https_proxy") or os.environ.get("HTTPS_PROXY")
                or os.environ.get("http_proxy") or os.environ.get("HTTP_PROXY")
                or DEFAULT_PROXY
            )
            handlers.append(urllib.request.ProxyHandler({"http": proxy, "https": proxy}))
        _OPENER = urllib.request.build_opener(*handlers)
    return _OPENER


def api(method: str, url: str, token: str, payload=None, headers=None,
        timeout: int = 180, retries: int = 3):
    h = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "User-Agent": "FilmCam-Release-Publisher",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    if headers:
        h.update(headers)
    body = None
    if payload is not None:
        if isinstance(payload, (dict, list)):
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            h.setdefault("Content-Type", "application/json")
        else:
            body = payload
    last_err: Exception | None = None
    for attempt in range(retries + 1):
        req = urllib.request.Request(url, data=body, headers=h, method=method)
        try:
            with opener().open(req, timeout=timeout) as r:
                raw = r.read()
                return r.status, (json.loads(raw) if raw else {})
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")[:500]
            raise RuntimeError(f"{method} {url} -> HTTP {e.code}: {detail}") from None
        except Exception as e:  # 网络抖动 / 代理断连，短暂退避后重试
            last_err = e
            if attempt < retries:
                log(f"  ! 网络异常（{type(e).__name__}），重试 {attempt + 1}/{retries}")
                time.sleep(1.0 + attempt)
    raise RuntimeError(f"{method} {url} 失败：{last_err}")


def git_log(n: int = 10) -> list[str]:
    p = subprocess.run(
        ["git", "log", "--pretty=%s", "-n", str(n)],
        cwd=ROOT, capture_output=True, text=True,
    )
    return [
        line.strip() for line in p.stdout.splitlines()
        if line.strip() and not line.startswith("Merge ")
    ]


# --------------------------------------------------------------------------- 业务


def list_releases(token: str) -> list[dict]:
    _, data = api("GET", f"{API}/releases?per_page=100", token)
    if not isinstance(data, list):
        raise RuntimeError("GET /releases 返回的不是列表")
    return data


def remote_latest_vc(releases: list[dict]) -> int | None:
    nums = []
    for r in releases:
        for a in r.get("assets", []):
            m = re.search(r"-vc(\d+)\.", a.get("name", ""))
            if m:
                nums.append(int(m.group(1)))
    return max(nums) if nums else None


def tag_for(vc: int, vn: str) -> str:
    return f"v{vn}-vc{vc}"


def build_notes(vc: int, vn: str, min_sdk: int | None,
                assets: dict[str, Path], digests: dict[str, str],
                extra: str) -> str:
    lines = [
        f"Relic Cam {vn} · versionCode {vc}",
        "",
        "**近期变更**（取自仓库提交记录）",
    ]
    for subject in git_log(10):
        lines.append(f"- {subject}")
    lines += [
        "",
        "**下载**",
        f"- `{assets['apk'].name}` — 侧载安装包"
        + (f"（minSdk {min_sdk} / Android 8.0+）" if min_sdk else ""),
        f"- `{assets['aab'].name}` — 仅供 Google Play Console 上传，普通用户请下 APK",
        "",
        "**离线承诺**：App 不申请 INTERNET 权限，照片全程本地处理、不上传任何服务器。"
        "侧载包在「关于 → 更新 → 点击检查」会跳到本 Releases 页；"
        "从 Play 安装的包走官方应用内更新。",
        "",
        "**安装说明**：APK 使用开发者上传密钥签名。若你在 Google Play 安装过测试版，"
        "两者签名不同，需先卸载旧版再安装本 APK。",
        "",
    ]
    if extra.strip():
        lines += [extra.strip(), ""]
    lines += ["**校验（sha256）**", ""]
    for ext in ("apk", "aab"):
        lines.append(f"- {ext.upper()}  `{digests[ext]}`")
    return "\n".join(lines)


def ensure_release(releases: list[dict], token: str, tag: str, vc: int,
                   vn: str, body: str, target: str,
                   refresh_notes: bool = False) -> tuple[dict, bool]:
    existing = next((r for r in releases if r["tag_name"] == tag), None)
    if existing:
        # 幂等重跑：只确保它是 Latest，默认不覆盖正文（可能含手工补充说明）
        patch = {"make_latest": "true"}
        if refresh_notes:
            patch["body"] = body
        api("PATCH", f"{API}/releases/{existing['id']}", token, payload=patch)
        _, refreshed = api("GET", f"{API}/releases/{existing['id']}", token)
        return refreshed, False
    _, created = api("POST", f"{API}/releases", token, payload={
        "tag_name": tag,
        "target_commitish": target,
        "name": f"Relic Cam {vn} · vc{vc}",
        "body": body,
        "draft": False,
        "prerelease": False,
        "make_latest": "true",
    })
    return created, True


def ensure_asset(release: dict, path: Path, token: str, digest: str) -> str:
    existing = next(
        (a for a in release.get("assets", []) if a["name"] == path.name), None
    )
    if existing and existing.get("digest") == f"sha256:{digest}":
        return "unchanged"
    if existing:
        api("DELETE", f"{API}/releases/assets/{existing['id']}", token)
        replaced = True
    else:
        replaced = False
    url = f"{UPLOAD}/releases/{release['id']}/assets?name=" + urllib.parse.quote(
        path.name, safe=""
    )
    try:
        api("POST", url, token, payload=path.read_bytes(),
            headers={"Content-Type": "application/octet-stream"}, timeout=300)
    except RuntimeError as e:
        if "already_exists" not in str(e):
            raise
        # 上一次请求其实已经成功、只是响应在代理上丢了：重试撞到同名资产。
        # 重新拉一次资产列表核对 sha256，匹配就当成功，别让整轮发布半途而废。
        _, refreshed = api("GET", f"{API}/releases/{release['id']}", token)
        same = next(
            (a for a in refreshed.get("assets", []) if a["name"] == path.name), None
        )
        if not same or same.get("digest") != f"sha256:{digest}":
            raise
        return "uploaded(response-lost)"
    return "replaced" if replaced else "uploaded"


def deprecate_old(releases: list[dict], current_tag: str, token: str) -> list[str]:
    banner = (
        "> ⚠️ **此版本已废弃**，其资产为旧构建。"
        f"请到 [{RELEASES_PAGE}]({RELEASES_PAGE}) 下载最新 Release。\n\n"
    )
    done = []
    for r in releases:
        if r["tag_name"] == current_tag or not r.get("assets"):
            continue  # 跳过本次 Release 与无资产的内测引导页
        body = r.get("body") or ""
        if DEPRECATED_MARK in body:
            continue
        api("PATCH", f"{API}/releases/{r['id']}", token,
            payload={"body": banner + body})
        done.append(r["tag_name"])
    return done


# --------------------------------------------------------------------------- 入口


def main() -> int:
    ap = argparse.ArgumentParser(description="同步 FilmCam 的 GitHub Release 资产")
    ap.add_argument("--check", action="store_true", help="只读检查：本地版本 vs 远端最新资产")
    ap.add_argument("--dry-run", action="store_true", help="只打印发布计划，不发写请求")
    ap.add_argument("--vc", type=int, help="覆盖 versionCode（补发历史版本用）")
    ap.add_argument("--no-deprecate", action="store_true", help="不给旧 Release 加废弃横幅")
    ap.add_argument("--refresh-notes", action="store_true",
                    help="复用已有 Release 时也用自动生成的正文覆盖（默认保留原正文）")
    ap.add_argument("--notes-extra", default="", help="追加到 Release 正文的补充说明")
    ap.add_argument("--no-proxy", action="store_true", help="不自动设置代理")
    args = ap.parse_args()

    if args.no_proxy:
        os.environ["FILMCAM_NO_PROXY"] = "1"
        log("(已禁用代理)")
    elif not any(
        os.environ.get(k) for k in ("https_proxy", "HTTPS_PROXY", "http_proxy", "HTTP_PROXY")
    ):
        log(f"(环境变量未设代理，使用项目默认 {DEFAULT_PROXY})")

    vc, vn, min_sdk = read_version()
    if args.vc:
        vc = args.vc
    assets = find_assets(vc, vn)
    token = get_token()

    log(f"本地版本   : versionName={vn} versionCode={vc}" +
        (f" minSdk={min_sdk}" if min_sdk else ""))
    for ext in ("apk", "aab"):
        log(f"  {ext.upper():<3} {assets[ext].name}  {assets[ext].stat().st_size:,} B")

    releases = list_releases(token)
    remote = remote_latest_vc(releases)
    log(f"远端最新资产: vc{remote if remote is not None else '无'}"
        f"（共 {len(releases)} 个 Release）")

    if args.check:
        if remote == vc:
            log("STATUS: IN_SYNC  ✓  GitHub 资产已是最新版本")
            return 0
        gap = (vc - remote) if remote is not None else None
        log(f"STATUS: STALE    ⚠  需要发布 vc{vc}"
            + (f"（落后 {gap} 个版本）" if gap else ""))
        return 2

    tag = tag_for(vc, vn)
    digests = {ext: sha256(assets[ext]) for ext in ("apk", "aab")}
    body = build_notes(vc, vn, min_sdk, assets, digests, args.notes_extra)

    log(f"\n计划：tag={tag}  name=Relic Cam {vn} · vc{vc}  make_latest=true")
    log(f"      资产 {assets['apk'].name}, {assets['aab'].name}")
    if args.dry_run:
        log("\n---- Release 正文预览 ----\n" + body + "\n---- 预览结束（未发送写请求）----")
        return 0

    release, created = ensure_release(
        releases, token, tag, vc, vn, body, "main", args.refresh_notes
    )
    log(f"{'已创建' if created else '复用已有'} Release id={release['id']}  {release['html_url']}")

    for ext in ("apk", "aab"):
        state = ensure_asset(release, assets[ext], token, digests[ext])
        log(f"  {ext.upper():<3} {state:<9} {assets[ext].name}")

    if not args.no_deprecate:
        marked = deprecate_old(releases, tag, token)
        log(f"  旧版废弃标记: {', '.join(marked) if marked else '无需处理'}")

    # 最终校验：重新拉取，逐个比对 digest 与大小
    _, final = api("GET", f"{API}/releases/{release['id']}", token)
    log("\n===== 远端最终状态 =====")
    ok = True
    for ext in ("apk", "aab"):
        p = assets[ext]
        a = next((x for x in final.get("assets", []) if x["name"] == p.name), None)
        if not a:
            log(f"  ✗ {p.name} 未出现在远端")
            ok = False
            continue
        match = a.get("digest") == f"sha256:{digests[ext]}" and a["size"] == p.stat().st_size
        ok &= match
        log(f"  {'✓' if match else '✗'} {a['name']}  {a['size']:,} B  {a.get('digest', '?')}")
        log(f"      {a['browser_download_url']}")
    log(f"\n发布页: {final['html_url']}")
    log(f"列表页: {RELEASES_PAGE}")
    log("STATUS: " + ("PUBLISHED ✓" if ok else "VERIFY_FAILED ⚠"))
    return 0 if ok else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:
        print(f"ERROR: {e}", file=sys.stderr)
        sys.exit(1)
