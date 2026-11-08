#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Play 上传前自检：一眼看出还缺什么。

用法（在 G:\\FilmCam\\play_console_upload 下）：
    python check_ready.py
"""

import json
import os
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SA_JSON = os.environ.get("PLAY_SA_JSON", os.path.join(HERE, "play-sa.json"))
AAB = "G:/FilmCam/app/build/outputs/bundle/release/app-release.aab"
EXPECT_PACKAGE = "com.filmcam.app"

ok = []
bad = []
warn = []


def line(status, text):
    print(f"[{status}] {text}")


# ---------- 1) AAB ----------
print("=" * 60)
print("1) AAB 安装包")
print("=" * 60)
if os.path.exists(AAB):
    size_mb = os.path.getsize(AAB) / 1024 / 1024
    line("OK", f"AAB 存在：{AAB}  ({size_mb:.1f} MB)")
    ok.append("AAB")

    # 在 AAB 里搜包名字符串（AAB 内 manifest 是 protobuf 编码，按字节串搜）
    found = False
    try:
        with zipfile.ZipFile(AAB) as z:
            names = z.namelist()
            targets = [n for n in names if n.endswith("AndroidManifest.xml")]
            for n in targets:
                data = z.read(n)
                if EXPECT_PACKAGE.encode() in data:
                    found = True
                    break
                    # 顺便判断错误包名
    except Exception as e:  # noqa: BLE001
        warn.append(f"读 AAB 出错：{e}")
        line("WARN", f"无法解析 AAB 内容：{e}")
    else:
        if found:
            line("OK", f"包名校验通过：{EXPECT_PACKAGE}")
        else:
            line("FAIL", f"AAB 里没找到包名 {EXPECT_PACKAGE}（请注意检查）")
            bad.append("AAB 包名")
else:
    line("FAIL", f"找不到 AAB：{AAB}")
    bad.append("AAB")

# ---------- 2) 服务账号密钥 ----------
print()
print("=" * 60)
print("2) 服务账号密钥 play-sa.json")
print("=" * 60)
if os.path.exists(SA_JSON):
    try:
        with open(SA_JSON, "r", encoding="utf-8") as f:
            key = json.load(f)
        missing = [k for k in ("type", "client_email", "private_key", "project_id") if k not in key]
        if missing:
            line("FAIL", f"JSON 存在但字段不全，缺少：{', '.join(missing)}")
            bad.append("密钥字段")
        elif key.get("type") != "service_account":
            line("FAIL", f"type 应为 service_account，实际是 {key.get('type')}")
            bad.append("密钥类型")
        else:
            line("OK", "密钥格式正确")
            line("OK", f"服务账号邮箱（Play Console 授权时填这个）：{key['client_email']}")
            line("OK", f"GCP 项目：{key['project_id']}")
            ok.append("密钥")
    except json.JSONDecodeError as e:
        line("FAIL", f"不是合法 JSON：{e}")
        bad.append("密钥JSON")
else:
    line("FAIL", "文件不存在 —— 需要你在 Google Cloud 里创建服务账号并下载 JSON 密钥")
    line("     ", f"期望路径：{SA_JSON}")
    bad.append("密钥")

# ---------- 3) 依赖 ----------
print()
print("=" * 60)
print("3) Python 依赖")
print("=" * 60)
try:
    import googleapiclient  # noqa: F401
    import google.auth  # noqa: F401

    line("OK", "google-api-python-client / google-auth 已安装")
    ok.append("依赖")
except ImportError:
    line("WARN", "当前 Python 里没有 Google 库（要用 upload_venv 里的 python 跑上传脚本）")
    venv_py = os.path.join(HERE, "upload_venv", "Scripts", "python.exe")
    if os.path.exists(venv_py):
        line("OK", f"upload_venv 存在：{venv_py}")
    else:
        line("FAIL", "upload_venv 不存在，需要重新创建虚拟环境")
        bad.append("venv")

# ---------- 4) 素材 ----------
print()
print("=" * 60)
print("4) 商店素材")
print("=" * 60)
assets = [
    ("play_ready/icon_hires_512.png", "512×512 图标"),
    ("play_ready/feature_graphic_1024x500.png", "1024×500 特色图"),
    ("play_ready/01_camera_1080x2160.png", "截图 1"),
    ("play_ready/02_gallery_1080x2160.png", "截图 2"),
    ("play_ready/03_developed_photo_1080x2160.png", "截图 3"),
]
for rel, label in assets:
    p = os.path.join(HERE, rel)
    if os.path.exists(p):
        line("OK", f"{label}：{rel}")
    else:
        line("FAIL", f"缺少 {label}：{rel}")
        bad.append(label)

# ---------- 汇总 ----------
print()
print("=" * 60)
if bad:
    print(f"结论：还差 {len(bad)} 项 —— {', '.join(bad)}")
    print("按 PLAY_SA_SETUP.md 里的步骤补齐即可。")
    sys.exit(1)
else:
    print("结论：全部就绪，可以跑上传脚本了：")
    print('  upload_venv\\Scripts\\python.exe upload_to_play.py')
    sys.exit(0)
