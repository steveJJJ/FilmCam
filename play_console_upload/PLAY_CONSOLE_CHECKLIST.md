# Relic Cam — Google Play 上架表单填写清单（照着勾）

> 应用包名 `com.filmcam.app`，显示名 **Relic Cam**，分类 **摄影 (Photography)**。
> 本文件逐节给出 Play Console 里每个表单的**确切选项**，可直接照抄勾选。
> 所有图片素材已生成在 `play_ready/` 目录（见 README.md）。
> AAB 包：`app/build/outputs/bundle/release/app-release.aab`（已签名，targetSdk 36，6 个 .so 全 16KB 对齐）。

---

## ▶ 当前进度与下一步（2026-11-08）

**已完成**
- [x] 代码侧：targetSdk 36、16KB 对齐、edge-to-edge、AAB 重出
- [x] 内购接入：billing 9.1.0 + `ProManager.kt` + 关于页「工程模式 PRO」入口，versionCode 53（JPEG 输入校正已定档）
- [x] 隐私政策：免费 + 内购表述，已推送上线 `https://stevejjj.github.io/relic-cam-privacy/`
- [x] 商店文案 / 图标 / 特色图 / 3 张截图 / 原生符号包 全部就绪
- [x] 商家账号（付款资料）已提交，银行账户改用储蓄卡

**接下来按顺序做**
1. **确认银行账户验证状态** → `payments.google.com` → 设置 → 付款方式
   - 「正在验证」→ 等 1~3 个工作日（可能需上传 90 天内对账单 / 回填小额打款）
   - 「已验证」→ 进第 2 步
2. **应用定价改「免费」**（⚠️ 不可逆）→ 商店设置 → 定价和分发
3. **传 AAB** → 测试 → 内部测试 → 上传 `RelicCam-v1.0.0-vc53.aab`
4. **传素材 + 填商店信息**（第 1 节）→ 图标 512 / 特色图 1024×500 / 3 张截图
5. **填 App content 全部问卷**（第 2 节）→ Data safety 全 No、无广告、目标受众 13-17+18+
6. **建 `pro_unlock` 商品并激活**（第 3 节，需第 1 步完成）
7. **把自己设为许可测试员** → 设置 → 许可测试，然后装机走一遍购买流程
8. **提交审核**

**可并行**：2~5 项都不依赖银行验证，现在就能做。

---

## 0. 上传前的硬性结论（已验证）
- targetSdk / compileSdk = **36** ✓
- 全部 6 个 `.so` 的 `PT_LOAD` 对齐 = **16384（16KB）**，无自带 libz ✓
- AAB 已签名（FILMCAM 密钥），Play App Signing 会再用上传密钥重签 ✓
- 真机冒烟：相机/相册/关于/照片查看 启动无崩溃；**暗房引擎初始化已修复**（见下方「重要修复」）

### ⚠️ 重要修复（已做，已重出 AAB）
移出自带 `libz.so/.so.1` 后，`libraw.so` 的 `DT_NEEDED: libz.so.1` 在设备上无法解析（系统只提供 `libz.so`），导致**暗房冲洗引擎崩溃（UnsatisfiedLinkError）**。
已把 `libraw.so` / `libraw_r.so` 的依赖从 `libz.so.1` 改为系统 `libz.so`（同库、ABI 兼容），重新构建并装机验证：`UnsatisfiedLinkError = 0`、引擎初始化失败 = 0。
**请务必用本次重新构建的 AAB 上传**（不要用工件里更早的版本）。

---

## 1. Store listing（商品详情）
| 字段 | 填写 |
|---|---|
| App name | **Relic Cam** |
| Short description（短描述，≤80 字） | 免费复古胶片相机：真实胶片色调、暗房冲洗；工程模式一次解锁永久可用。 |
| Full description（长描述） | 见下方「描述文案」 |
| App icon | 上传 `play_ready/icon_hires_512.png`（512×512） |
| Feature graphic | 上传 `play_ready/feature_graphic_1024x500.png`（1024×500） |
| Phone screenshots | 上传 `play_ready/01_camera_1080x2160.png`、`02_gallery_1080x2160.png`、`03_developed_photo_1080x2160.png`（手机须 2–8 张；已按 2:1 合规裁好） |
| Category | **Photography（摄影）** |
| Contact email | 填你的支持邮箱 |
| Website（可选） | 你的主页/官网，没有可留空 |
| Privacy Policy | **必填**（已提供并核验）→ `https://stevejjj.github.io/relic-cam-privacy/` |

### 描述文案（可直接复制）
**中文（主）**
```
Relic Cam 把你的手机变成一台复古胶片相机 —— 免费下载，工程模式（Pro）一次解锁、永久可用。

- 真实胶片模拟：内置多款经典胶片色彩（如 Kodak Gold 200、Cinestill 800T 等），拍摄即带胶片味道。
- 暗房冲洗：在手机里完成「冲卷」，反差、颗粒、色调由冲洗参数决定，所见即所得。
- 胶片质感：成片带胶片齿孔、片基与日期戳，像真的从暗房里拿出来。
- 工程模式（Pro）：面向发烧友的专家面板 —— 手工校准输入曝光、放大机曝光、乳剂与印相层参数，把每一卷冲出你想要的味道。
- 全本地处理：所有拍摄与冲洗都在设备上完成，照片不会上传到任何服务器。
- 简洁取景器：专注构图与曝光，没有花哨的滤镜商城。

免费下载。拿起手机，回到胶片时代。
```
**English（备选）**
```
Relic Cam turns your phone into a vintage film camera — free to download, with Engineering Mode (Pro) as a single one-time unlock you keep forever.

- Authentic film looks powered by real film profiles (Kodak Gold 200, Cinestill 800T, and more).
- In-app darkroom: develop your roll on-device with controllable contrast, grain, and tone.
- Film aesthetics: sprocket holes, film rebate, and date stamps on every frame.
- Engineering Mode (Pro): an expert panel for enthusiasts — hand-tune input exposure, enlarger exposure, and emulsion / printing-layer parameters to get exactly the look you want.
- Fully on-device: capture and develop locally; your photos are never uploaded.
- A clean viewfinder built for composition and exposure.

Free to download. Pick up your phone and step back into the film era.
```

---

## 2. App content（应用内容）— 逐节勾选

### 2.1 Privacy Policy（隐私政策）
- 选「提供隐私政策网址」→ 粘贴你的隐私政策 URL。
- 若还没有：先发布一份（说明「本应用不收集、不上传任何用户数据，仅在本机使用相机与相册」），再回来填。

### 2.2 App access（应用访问）
- 选 **「所有功能都无需特殊访问即可使用」**（应用无登录、无账号）。
- 不要勾选「需要登录/序列号」等。

### 2.3 Ads（广告）
- 选 **「否，我的应用不含广告」**。

### 2.4 Content rating（内容分级）
- 用内容分级问卷；分类选 **摄影/工具** 类。
- 暴力、色情、毒品、不良语言等全部选「无 / 不适用」。
- 预期结果：Everyone（所有人）或 Teen（青少年）；以问卷实际答案为准。

### 2.5 Target audience and content（目标受众）
- 年龄组勾选：**13–17** 与 **18 岁以上**。
- **不要**勾选「13 岁以下 / 儿童」。
- 确认问题选：**「否，本应用并非为儿童设计」**。
- 若出现「以儿童为对象」的后续问卷，选「不适用」。

### 2.6 Data safety（数据安全）— 关键
逐一回答：
1. 「Does your app collect or share any required or optional user data?」
   → **No**（应用仅在设备本地使用相机/相册，不上传、不共享任何数据）。
2. 选完后系统显示：**Your app does not collect or share any user data.**
3. 如被追问具体数据类型（照片/相机等）：均选「不收集 / 不共享」。
> 说明：应用确实会请求相机与存储权限，但**数据从不离开设备**，因此按「不收集/不共享」申报即可。若日后加入云同步/登录，需重新申报。

### 2.7 Government apps（政府应用）— No
- 选「否」。

### 2.8 Financial features（金融功能）— None
- 选「None」（无支付、无订阅、无虚拟货币）。

---

## 3. Pricing & distribution（定价与发布）+ 内购商品
- 价格：**免费（Free）**。⚠️ 注意 Play 政策：应用一旦设为免费就**不能改回付费**；我们已确定走「免费下载 + 内购解锁工程模式」，所以这里必须选 Free。
- **创建内购商品**
  - **前置：先建商家账号（付款资料）** —— 若「创收」页面提示「若要通过此应用变现，请设置一个商家账号」，点该按钮，或走 主菜单 →「设置 → 开发者账号 → 付款资料」→ 创建。个人主体可选「个人」，无需注册公司。需准备：法定姓名（与证件逐字一致）、地址、电话、支持邮箱、支持美元的银行账户（SWIFT/BIC + 英文账户名）、税表 W-8BEN（个人）/ W-8BEN-E（组织）。**不填税表会按 30% 预扣**；姓名须与银行开户名完全一致，否则退回。可能需回填 Google 打来的小额验证款。审核期间不阻塞上传 AAB / 填商店信息，但「创收」菜单要等它建好才解锁。
  - 路径：左侧菜单 **「创收」/「通过 Play 创收」(Monetize with Play)** → 展开 **「商品」(Products)** → **「应用内商品」(In-app products)** → **「创建商品」(Create product)**
  - 注意：新版 Console 里这一项也可能叫「一次性商品」(One-time products)。**旧版「应用内商品」与代码里的 `ProductType.INAPP` 直接对应，优先用这个。**
  - 前提条件（缺任一条这一节就不会出现或无法创建）：
    1. 应用「定价」已设为免费/付费（去「定价和分发」先设一次）
    2. **付款资料（Payments profile）已创建并验证** —— 主菜单「设置 → 开发者账号 → 付款资料」
    3. **已上传过至少一个 AAB 到任一轨道**（正式版或测试轨道均可，不必发布）
    4. 应用未处于被移除/暂停状态
  - 字段填写：
    - 商品 ID：`pro_unlock`（**必须与代码完全一致，创建后永久不可改，也不能删除，只能停用**）
    - 名称：工程模式（Pro） / Engineering Mode (Pro)
    - 描述：一次性解锁工程模式专家面板，永久可用
    - 价格：自定（建议 ¥18 / $2.99 一档），**创建后必须点「激活」(Activate)**
    - 若面向欧盟用户，还需选 Withdrawal Right Type：`DIGITAL_CONTENT`
  - ⚠️ **不点「激活」= 用户点购买时什么都不会弹出**。这是最常见的踩坑点。
- 内购说明文案要点：一次性买断不是订阅；重装/换机可恢复；购买由 Google Play 处理。
- 国家/地区：默认全选，或按需。
- 设备分类：手机 + 平板（应用支持横竖屏）。
- Managed Google Play（企业分发）：不需要可不勾。

---

## 4. 上传与提交
1. Play Console → 新建应用 → 填 1 节的基础信息。
2. **测试 → 内部测试（Internal testing）** 先跑一轮 → 上传 `RelicCam-v1.0.0-vc53.aab`（⚠️ vc47~vc52 全部作废：vc48 是 libz 补丁写错字节的坏包；vc49~vc52 是调色标定中间版，只在本机验证过。**上架必须传 vc53**）。
3. 依次补全第 2 节所有 App content 表单（标「待处理」的都要点完成）。
4. 填完 Store listing（第 1 节）并上传图标/特图/截图。
5. 提交审核。

---

## 5. 待你确认的两件事
1. ~~**隐私政策 URL**~~ ✅ 已提供并核验：`https://stevejjj.github.io/relic-cam-privacy/`（已对照 release manifest 核实：无 INTERNET 权限、allowBackup=false、权限清单与政策逐字一致，Play 交叉核对不会打回）。
2. **商标**：当前应用内仍显示「CONTAX T3」字样（快门按钮上方、EXIF、部分文案）。上传前建议替换为自有命名，避免品牌异议。需要我批量改掉可随时说。
