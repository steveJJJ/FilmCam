#!/usr/bin/env python3
"""
Relic Cam — Google Play 自动上传脚本
用法（在 upload_venv 激活后）：
  python upload_to_play.py
依赖：google-api-python-client, google-auth, google-auth-httplib2
前置：
  1) 在 Play Console 创建应用，包名 = com.filmcam.app
  2) 创建服务账号并下载 JSON 密钥，放到本脚本同目录 play-sa.json（或设环境变量 PLAY_SA_JSON）
  3) 把该服务账号在 Play Console 授予「发布管理员 / Release manager」权限
轨道默认 internal（草稿，不自动发布）；可用环境变量 PLAY_TRACK 改（如 production）。
"""
import os, sys, json
from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload
from google.auth.transport.requests import AuthorizedSession

HERE = os.path.dirname(os.path.abspath(__file__))
PACKAGE = "com.filmcam.app"
SA_JSON = os.environ.get("PLAY_SA_JSON", os.path.join(HERE, "play-sa.json"))
AAB = os.path.join("G:/FilmCam/app/build/outputs/bundle/release/app-release.aab")
TRACK = os.environ.get("PLAY_TRACK", "internal")

ASSETS = os.path.join(HERE, "play_ready")
ICON = os.path.join(ASSETS, "icon_hires_512.png")
FEATURE = os.path.join(ASSETS, "feature_graphic_1024x500.png")
PHONES = [
    os.path.join(ASSETS, "01_camera_1080x2160.png"),
    os.path.join(ASSETS, "02_gallery_1080x2160.png"),
    os.path.join(ASSETS, "03_developed_photo_1080x2160.png"),
]
CONTACT_EMAIL = "837226902ah@gmail.com"

LISTINGS = {
    "en-US": {
        "title": "Relic Cam",
        "shortDescription": "Free film camera app: real film looks, on-device darkroom. Pro one-time unlock.",
        "fullDescription": (
            "Relic Cam turns your phone into a vintage film camera \u2014 free to download, with Engineering Mode (Pro) as a single one-time unlock you keep forever.\n\n"
            "- Authentic film looks powered by real film profiles.\n"
            "- In-app darkroom: develop your roll on-device with controllable contrast, grain, and tone.\n"
            "- Film aesthetics: sprocket holes, film rebate, and date stamps on every frame.\n"
            "- Engineering Mode (Pro): an expert panel for enthusiasts \u2014 hand-tune input exposure, enlarger exposure, and emulsion / printing-layer parameters to get exactly the look you want.\n"
            "- Fully on-device: capture and develop locally; your photos are never uploaded.\n"
            "- A clean viewfinder built for composition and exposure.\n\n"
            "Free to download. Pick up your phone and step back into the film era."
        ),
        "contactEmail": CONTACT_EMAIL,
    },
    "zh-CN": {
        "title": "Relic Cam",
        "shortDescription": "免费复古胶片相机：真实胶片色调、暗房冲洗；工程模式一次解锁永久可用。",
        "fullDescription": (
            "Relic Cam \u628a\u4f60\u7684\u624b\u673a\u53d8\u6210\u4e00\u53f0\u590d\u53e4\u80f6\u7247\u76f8\u673a \u2014\u2014 \u514d\u8d39\u4e0b\u8f7d\uff0c\u5de5\u7a0b\u6a21\u5f0f\uff08Pro\uff09\u4e00\u6b21\u89e3\u9501\u3001\u6c38\u4e45\u53ef\u7528\u3002\n\n"
            "- \u771f\u5b9e\u80f6\u7247\u6a21\u62df\uff1a\u5185\u7f6e\u591a\u6b3e\u7ecf\u5178\u80f6\u7247\u8272\u5f69\uff0c\u62cd\u6444\u5373\u5e26\u80f6\u7247\u5473\u9053\u3002\n"
            "- \u6697\u623f\u51b2\u6d17\uff1a\u5728\u624b\u673a\u91cc\u5b8c\u6210\u300c\u51b2\u5377\u300d\uff0c\u53cd\u5dee\u3001\u9897\u7c92\u3001\u8272\u8c03\u7531\u51b2\u6d17\u53c2\u6570\u51b3\u5b9a\uff0c\u6240\u89c1\u5373\u6240\u5f97\u3002\n"
            "- \u80f6\u7247\u8d28\u611f\uff1a\u6210\u7247\u5e26\u80f6\u7247\u9f7f\u5b54\u3001\u7247\u57fa\u4e0e\u65e5\u671f\u6233\uff0c\u50cf\u771f\u7684\u4ece\u6697\u623f\u91cc\u62ff\u51fa\u6765\u3002\n"
            "- \u5de5\u7a0b\u6a21\u5f0f\uff08Pro\uff09\uff1a\u9762\u5411\u70e7\u53cb\u7684\u4e13\u5bb6\u9762\u677f \u2014\u2014 \u624b\u5de5\u6821\u51c6\u8f93\u5165\u66dd\u5149\u3001\u653e\u5927\u673a\u66dd\u5149\u3001\u4e73\u5242\u4e0e\u5370\u76f8\u5c42\u53c2\u6570\uff0c\u628a\u6bcf\u4e00\u5377\u51b2\u51fa\u4f60\u60f3\u8981\u7684\u5473\u9053\u3002\n"
            "- \u5168\u672c\u5730\u5904\u7406\uff1a\u6240\u6709\u62cd\u6444\u4e0e\u51b2\u6d17\u90fd\u5728\u8bbe\u5907\u4e0a\u5b8c\u6210\uff0c\u7167\u7247\u4e0d\u4f1a\u4e0a\u4f20\u5230\u4efb\u4f55\u670d\u52a1\u5668\u3002\n"
            "- \u7b80\u6d01\u53d6\u666f\u5668\uff1a\u4e13\u6ce8\u6784\u56fe\u4e0e\u66dd\u5149\uff0c\u6ca1\u6709\u82b1\u5316\u7684\u6ee4\u955c\u5546\u57ce\u3002\n\n"
            "\u514d\u8d39\u4e0b\u8f7d\u3002\u62ff\u8d77\u624b\u673a\uff0c\u56de\u5230\u80f6\u7247\u65f6\u4ee3\u3002"
        ),
        "contactEmail": CONTACT_EMAIL,
    },
}

SCOPES = ["https://www.googleapis.com/auth/androidpublisher"]


def fail(msg, resp=None):
    print("❌ " + msg)
    if resp is not None:
        try:
            print("   API 响应:", resp.text if hasattr(resp, "text") else resp)
        except Exception:
            pass
    sys.exit(1)


def main():
    if not os.path.exists(SA_JSON):
        fail(f"找不到服务账号密钥: {SA_JSON}\n请先在 Play Console 创建服务账号并下载 JSON 放到该路径（或设 PLAY_SA_JSON）。")
    if not os.path.exists(AAB):
        fail(f"找不到 AAB: {AAB}")

    print("🔐 用服务账号鉴权…")
    creds = service_account.Credentials.from_service_account_file(SA_JSON, scopes=SCOPES)
    service = build("androidpublisher", "v3", credentials=creds, cache_discovery=False)
    print("   鉴权成功。")

    print("🆕 创建编辑 (edit)…")
    edit = service.edits().insert(packageName=PACKAGE, body={}).execute()
    edit_id = edit["id"]
    print(f"   editId = {edit_id}")

    print("📦 上传 AAB…")
    bundle = (
        service.edits()
        .bundles()
        .upload(packageName=PACKAGE, editId=edit_id,
                media_body=MediaFileUpload(AAB, mimetype="application/octet-stream"))
        .execute()
    )
    version_code = bundle["versionCode"]
    print(f"   versionCode = {version_code}")

    print(f"🎯 挂到轨道 [{TRACK}]（草稿，不自动发布）…")
    service.edits().tracks().update(
        packageName=PACKAGE, editId=edit_id, track=TRACK,
        body={"releases": [{"versionCodes": [version_code], "status": "draft"}]},
    ).execute()
    print("   轨道已更新。")

    for lang, info in LISTINGS.items():
        print(f"📝 写商店文案 [{lang}]…")
        service.edits().listings().update(
            packageName=PACKAGE, editId=edit_id, language=lang, body=info
        ).execute()
        # icon
        print(f"   ├─ 图标…")
        up = service.edits().images().upload(
            packageName=PACKAGE, editId=edit_id, language=lang, imageType="icon",
            media_body=MediaFileUpload(ICON, mimetype="image/png")).execute()
        service.edits().images().update(
            packageName=PACKAGE, editId=edit_id, language=lang, imageType="icon",
            body={"images": [{"id": up["image"]["id"]}]}).execute()
        # feature graphic
        print(f"   ├─ 特色图…")
        up = service.edits().images().upload(
            packageName=PACKAGE, editId=edit_id, language=lang, imageType="featureGraphic",
            media_body=MediaFileUpload(FEATURE, mimetype="image/png")).execute()
        service.edits().images().update(
            packageName=PACKAGE, editId=edit_id, language=lang, imageType="featureGraphic",
            body={"images": [{"id": up["image"]["id"]}]}).execute()
        # phone screenshots
        print(f"   └─ {len(PHONES)} 张手机截图…")
        ids = []
        for p in PHONES:
            up = service.edits().images().upload(
                packageName=PACKAGE, editId=edit_id, language=lang, imageType="phoneScreenshots",
                media_body=MediaFileUpload(p, mimetype="image/png")).execute()
            ids.append({"id": up["image"]["id"]})
        service.edits().images().update(
            packageName=PACKAGE, editId=edit_id, language=lang, imageType="phoneScreenshots",
            body={"images": ids}).execute()

    print("✅ 提交编辑…")
    service.edits().commit(packageName=PACKAGE, editId=edit_id).execute()
    print("🎉 上传完成！")
    print(f"   包名: {PACKAGE}  版本: {version_code}  轨道: {TRACK}（草稿）")
    print("   之后请在 Play Console 补全 App content（隐私政策 URL、Data safety、目标受众、内容分级等），")
    print("   再到对应轨道把草稿发布 / 送审。")


if __name__ == "__main__":
    main()
