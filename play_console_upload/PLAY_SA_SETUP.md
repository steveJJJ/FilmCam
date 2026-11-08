# 获取 play-sa.json（Play 服务账号密钥）—— 分步指引

> **这个文件必须由你本人生成。** 它是你 Google Cloud 项目里一个「服务账号」的私钥，
> 只有你的 Google 账号能下载。任何工具（包括我）都无法凭空生成它。

前置条件：Play Console 里已经有一个包名为 `com.filmcam.app` 的应用。
如果你之前建成了 `com.reliccam.app`，先把那个删掉重建（包名一旦创建不可修改）。

---

## 第 1 步：在 Play Console 打开 API 访问

直接访问（会自动定位到你的开发者账号）：

```
https://play.google.com/console/api-access
```

或：Play Console 左下角 **设置（Settings）→ API 访问（API access）**

页面里点 **启用 API 访问 / 关联 Google Cloud 项目**，按提示创建或关联一个 Cloud 项目
（如果没有项目，选「创建新项目」，等它自动建好）。

---

## 第 2 步：在 Google Cloud 创建服务账号并下载 JSON 密钥

在「API 访问」页面的 **服务账号（Service accounts）** 区域，点
**创建服务账号 / Create new service account**。它会跳转到：

```
https://console.cloud.google.com/iam-admin/serviceaccounts
```

1. 点顶部 **创建服务账号（Create Service Account）**
2. 服务账号名称：随便填，例如 `relic-cam-uploader`
3. 点 **完成 / 创建并继续**（角色**不用**在这里选，Play 那边授权）
4. 在服务账号列表中点击刚建的那个 → 切到 **密钥（Keys）** 标签
5. **添加密钥 → 创建新密钥 → 选 JSON → 创建**
6. 浏览器会**自动下载**一个 `.json` 文件（文件名类似 `myproject-1a2b3c.json`）

> ⚠️ 这个 JSON 里含私钥，**不要上传到任何公开仓库**。用完可以随时在 Cloud 里删掉并重新生成。

---

## 第 3 步：回到 Play Console 给这个服务账号授权

回到 **设置 → API 访问**，在服务账号列表里找到刚建的那个，
点右侧 **授予访问权 / Grant access**（或在「用户和权限」里点「邀请新用户」，
把第 2 步下载的 JSON 里 `client_email` 的值填进去 —— 形如
`relic-cam-uploader@xxxx.iam.gserviceaccount.com`）。

在授权页面里设置：

| 项目 | 选什么 |
|---|---|
| 应用权限（App permissions） | **所有应用 / 全选**（或至少勾上 `com.filmcam.app`） |
| 账号权限（Account permissions） | **发布管理员（Release manager）** 或 管理员（Admin） |

> 只给「查看」权限会报 `403`，必须给到能发版的角色。

保存。授权一般**立即生效**（个别账号需要等几分钟）。

---

## 第 4 步：把 JSON 放到指定路径

把下载的 `.json` **重命名为** `play-sa.json`，放到：

```
G:\FilmCam\play_console_upload\play-sa.json
```

（也可以放任意位置，然后用环境变量指定：`set PLAY_SA_JSON=D:\xxx\mykey.json`）

---

## 第 5 步：自检 + 上传

```bat
cd /d G:\FilmCam\play_console_upload
python check_ready.py
```

全部 `[OK]` 后执行上传：

```bat
upload_venv\Scripts\python.exe upload_to_play.py
```

脚本会：上传 AAB → 挂到 **内部测试（internal）草稿轨道** → 写入中英文商店文案 →
上传图标 / 特色图 / 3 张截图 → 提交编辑（**不会自动发布**）。

---

## 常见卡点

| 报错 / 现象 | 原因 | 处理 |
|---|---|---|
| `403 Resource not accessible by integration` | 这里是 **GitHub** 的报错，与本流程无关 | 忽略 |
| `The caller does not have permission` | 服务账号没被授权，或角色太低 | 回第 3 步，角色选「发布管理员」，应用权限选「所有应用」 |
| `Package name must be ...` | Play 应用包名 ≠ AAB 包名 | Play 里必须是 `com.filmcam.app` |
| `APK or Android App Bundle has already been uploaded` | versionCode 重复 | 提高 `versionCode` 后重新打包 |
| Cloud 里找不到「创建服务账号」入口 | 项目还没启用 Play API | 回第 1 步，先点「启用 API 访问」 |
| 服务账号列表里是空的 | Cloud 项目没关联上 | 在 API 访问页点「关联现有项目」，选你刚建的那个 |

---

## 如果你不想折腾密钥

**完全不需要它也能上架** —— 直接在 Play Console 网页手动上传就行，一次而已：

1. Play Console → 你的应用 → **测试 → 内部测试 → 创建新版本**
2. 上传 `G:\FilmCam\play_console_upload\RelicCam-v48-com.filmcam.app.aab`
3. 按 `PLAY_CONSOLE_CHECKLIST.md` 填商店信息、传素材、填 App content
4. 送审

服务账号只是把上面这些变成一条命令，**不是上架的必需品**。
