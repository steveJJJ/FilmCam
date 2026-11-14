# Relic Cam — Google Play 上架表单填写清单（照着勾）

> 应用包名 `com.filmcam.app`，显示名 **Relic Cam**，分类 **摄影 (Photography)**。
> 本文件逐节给出 Play Console 里每个表单的**确切选项**，可直接照抄勾选。
> 所有图片素材已生成在 `play_ready/` 目录（见 README.md）。
> AAB 包：`app/build/outputs/bundle/release/app-release.aab`（已签名，targetSdk 36，6 个 .so 全 16KB 对齐）。

---

## ▶ 当前进度与下一步（2026-11-08）

**已完成**
- [x] 代码侧：targetSdk 36、16KB 对齐、edge-to-edge、AAB 重出
- [x] 内购接入：billing 9.1.0 + `ProManager.kt` + 关于页「工程模式 PRO」入口（vc48 起）
- [x] 隐私政策：免费 + 内购表述，已推送上线 `https://stevejjj.github.io/relic-cam-privacy/`
- [x] 商店文案 / 图标 / 特色图 / 3 张截图 / 原生符号包 全部就绪
- [x] 商家账号（付款资料）已提交，银行账户改用储蓄卡
- [x] **GitHub Release 资产已同步 vc80**（2026-11-14；此前停在 vc49，落后 16 个版本，vc72 起每次迭代都同步）→ 后续每次迭代按第 4.5 节执行

**接下来按顺序做**
1. **确认银行账户验证状态** → `payments.google.com` → 设置 → 付款方式
   - 「正在验证」→ 等 1~3 个工作日（可能需上传 90 天内对账单 / 回填小额打款）
   - 「已验证」→ 进第 2 步
2. **应用定价改「免费」**（⚠️ 不可逆）→ **商品 > 应用定价**（旧版：商店发布 > 定价和分发范围）；逐字段见 `PLAY_PRICING_FILL_GUIDE.md` 第 3 节
3. **传 AAB** → 测试 → 内部测试 → 上传 `RelicCam-v1.0.0-vc80.aab`（每次发版后改这里的版本号）
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
- **v67 反馈入口合并**：删除「复制 / 分享设备诊断报告」两个重复选项，
  诊断报告全文**自动附进「发邮件反馈」正文**（点一下即拉起邮件客户端），
  列表回到「发邮件反馈 / 加微信进群反馈」两项。报告走纯文本内嵌、不加附件，
  不引 FileProvider，零新增权限 / 零新增组件；新增 `FeedbackMail` + 6 例单测，
  单测共 276 例。中英文文案同步。
- **v66 新增「白边相框」第三档**：设置 → 胶片相框循环到「白边相框」。
  暗房相纸留白，四边绝对等宽 6%（短边）、暖白 `#F7F5F0`、无描边阴影、
  日期戳仍留在画面内。白边由 App 在两条冲洗通路的落盘前外扩，
  **不下发任何引擎 `overscan_*`**；导入照片同样适用，取景器不显示白边。
  `FrameFormat` 由 OFF/FULL 扩为 OFF/FULL/WHITE，`isOn` 改按 `overscanFormat != null`
  判定，白边档自动跳过引擎片门自检；EXIF 尺寸改用外扩后的最终值。
  新增 `WhiteBorderRenderer` + 9 例单测，单测共 270 例。**零新增权限**。
- **v65 新增「检查更新」（A+B 组合，零新增权限）**：关于页规格表新增一行。
  Play 安装的包走官方 `com.google.android.play:app-update:2.1.0` FLEXIBLE 应用内更新
  （后台下载、用户确认后 completeUpdate）；侧载包 / 库不可用 / 不允许 flexible
  一律 `ACTION_VIEW` 跳 GitHub Releases 页，Play 包失败则跳商店详情页。
  **App 自身不联网**：aapt2 实测合并清单仍无 INTERNET / ACCESS_NETWORK_STATE /
  REQUEST_INSTALL_PACKAGES，隐私政策与商店「完全离线」文案一个字未改；
  不自建版本接口、不下载 APK 自安装（Play 政策禁止自更新）。
- **v64 修复诊断版取证缺陷**：`gl.texMatrix` 以前只采前两帧，冷启动会
  把 SurfaceTexture 未初始化的**单位阵**永久写进报告；现在每次矩阵变化才记录
  （FloatArray 比较 + 3 位小数精度对齐的 epsilon），导出时强制以内存最新全文覆写。
  真机验证：vc63 冷启动 = 单位阵，vc64 冷启动 = 真实旋转矩阵。**仍为零行为改动**。
- **v63 内测诊断版（只读取证、零行为改动）**：新增 `DeviceGeometryReport` 聚合器与各链路
  埋点，把 WindowMetrics / insets / contentRect / previewSize / 候选预览尺寸 /
  SurfaceTexture transformMatrix / uMode / surface-view 一致性写进
  `filmcam_device_report.txt`；「关于 → 问题反馈」新增一键复制 / 分享，**不受 PRO 限制**。
  用于 vivo X300 Pro 等机型先取回数据再定 P0，不猜参数；正式上架可等 P0 修复后的下一版。
- **v62 外置闪光跨机型 P0**：无 MANUAL_SENSOR 的机型降级为 BLE 点灯 + HAL
  自动曝光（不再手写 AE_OFF / SENSOR_* 导致整帧失败）；快门按设备真实上限收敛，
  被截断后按 `ISO × t` 守恒反算 ISO，避免闪光帧凭空欠 1~2 EV。
- **v60 修复「外置档 App 快门不点灯」**：根因是点灯指令 `flash_trigger.hex`
  放在会被卸载/清数据删掉的外部私有目录，而旧实现读到文件缺失就整帧跳过。
  现在内置默认 `05 00` 兜底，外部文件只作调参覆盖；无 BLE 会话也会留日志并重建。
- **v59 外置闪光链路健壮性**：`FlashController.release()` 清空 `bleFlash` 引用
  （否则 startScan 幂等判断会让灯再也连不回来）；建连异常不再冒泡吞掉档位切换；
  `FlashDiag` 增加 cycleFlashMode / keyDown / startScan / 恢复建连日志。
- **v58 跨机型屏幕兼容**：新增 `ChromeSafeArea`，按「预览内容矩形之外的可用留白 + 系统栏 inset」
  等比缩放机身控件的 paddingTop / margin，并夹紧保证永不压进预览画面；窄屏按比例收窄顶栏。
  S25U 真机验证 `changed=false`（逐像素等价），横屏布局不介入。
  诊断文件 `adb pull /sdcard/Android/data/com.filmcam.app/files/filmcam_layout.txt`。
- **v57 无边框政策整改**：已改用官方 `enableEdgeToEdge()`（androidx.activity 1.9.0），删除
  `android:statusBarColor` / `android:navigationBarColor` 与 `window.statusBarColor` /
  `window.navigationBarColor`（API 35 弃用），不再直接使用 `SYSTEM_UI_FLAG_LIGHT_*`（API 30 弃用），
  图标深浅统一走 `WindowInsetsControllerCompat`。Play 的「无边框 / 弃用 API」两条提示应在重传后消失。

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

- 真实胶片模拟：内置多款经典胶片色彩，拍摄即带胶片味道。
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

- Authentic film looks powered by real film profiles.
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

### 2.9 Foreground service permissions（前台服务声明）— 最容易秒拒的一节
入口：**政策与计划 → App content → Foreground service permissions → 开始 / Manage**
（上传过含该权限的 AAB 后才会出现；我们已传过）

- **声明类型必须与 manifest 完全一致**：代码是 `FOREGROUND_SERVICE_DATA_SYNC` +
  `android:foregroundServiceType="dataSync"` → 这里只能选 **Data sync**。
  不要改选 mediaProcessing / mediaPlayback 等（类型不匹配会直接驳回）。
  `POST_NOTIFICATIONS` 已声明、合并清单无 `INTERNET`，代码侧已核对无误。
- use case **只勾本地文件处理类**（Local file processing / Import-export / on-device
  processing 一类，以 Console 实际措辞为准）；**不要勾任何云端上传下载、备份同步、
  网络传输** —— 应用没有 INTERNET 权限，勾了就是自相矛盾。
- 三问的答法：
  1. **核心功能**：用户在暗房点「冲洗整卷」，需要对整卷逐帧在设备本地做胶片渲染
     （每帧数秒、整卷数分钟），期间必须连续执行并显示可见进度。
  2. **用户路径**：暗房 → 点「冲洗」→ 出现持续通知显示帧数进度 → 可切到后台 / Home
     → 通知栏持续可见 → 完成后返回查看成片。
  3. **为何不能用 WorkManager**：任务由用户主动发起、需立即连续执行并提供实时进度，
     WorkManager 会被 Doze / 电量配额延迟，无法满足「冲洗中随时回来看进度」的预期。
- **演示视频**：`play_console_upload/fgs_demo.mp4`（50s / 1080p / 36MB）。
  先传 YouTube 设为**不公开（Unlisted，不是 Private）**，用**无痕窗口**确认链接能播放，
  再粘贴 URL。视频里必须能看清：① 点冲洗 ② 通知栏出现持续通知 ③ 切后台通知仍在
  ④ 返回看到成片。
- **常见秒拒原因**（16:06 提交 / 16:07 被拒属于这一类，通常是自动校验）：
  视频链接无痕窗口打不开或设成了 Private；use case 勾了云端 / 网络项；
  三问没说清「为什么不能延迟执行」；声明类型与 manifest 不一致；
  通知不是持续的或没有进度。被拒后改完重新提交即可，提交 ID 会递增，不影响已上传的 AAB。

---

## 3. Pricing & distribution（定价与发布）+ 内购商品

> 2026-11-12 更新：逐字段填写说明独立成文 → **`PLAY_PRICING_FILL_GUIDE.md`**
> （新版菜单路径、一次性商品、付款资料/税表、许可测试、7 个踩坑）。本节以下为要点。
> ⚠️ 2025 年起旧「应用内商品」已改名为「一次性商品」；**2025-10-27 起定价模板停用**，价格须逐个商品设置。

- 价格：**免费（Free）**。⚠️ 注意 Play 政策：应用一旦设为免费就**不能改回付费**；我们已确定走「免费下载 + 内购解锁工程模式」，所以这里必须选 Free。
- **创建内购商品**
  - **前置：先建商家账号（付款资料）** —— 若「创收」页面提示「若要通过此应用变现，请设置一个商家账号」，点该按钮，或走 主菜单 →「设置 → 开发者账号 → 付款资料」→ 创建。个人主体可选「个人」，无需注册公司。需准备：法定姓名（与证件逐字一致）、地址、电话、支持邮箱、支持美元的银行账户（SWIFT/BIC + 英文账户名）、税表 W-8BEN（个人）/ W-8BEN-E（组织）。**不填税表会按 30% 预扣**；姓名须与银行开户名完全一致，否则退回。可能需回填 Google 打来的小额验证款。审核期间不阻塞上传 AAB / 填商店信息，但「创收」菜单要等它建好才解锁。
  - 路径：左侧菜单 **「通过 Google Play 创收」(Monetize with Play)** → **「商品」(Products)** → **「一次性商品」(One-time products)** → **「创建一次性商品」**
  - ⚠️ 2025 年后旧「应用内商品 (In-app products)」已改叫「一次性商品 (One-time products)」。**就用新版一次性商品**：代码 `ProManager.kt` 的 `ProductType.INAPP` 与它直接对应，billing 9.1.0 + `enableOneTimeProducts()` 已按新模型接入。
  - 前提条件（缺任一条这一节就不会出现或无法创建）：
    1. 应用「定价」已设为免费/付费（去「商品 > 应用定价」先设一次）
    2. **付款资料（Payments profile）已创建并验证** —— 主菜单「设置 → 开发者账号 → 付款资料」
    3. **已上传过至少一个 AAB 到任一轨道**（正式版或测试轨道均可，不必发布）
    4. 应用未处于被移除/暂停状态
  - 字段填写：
    - 商品 ID：`pro_unlock`（**必须与代码完全一致，创建后永久不可改，也不能删除，只能停用**）
    - 名称：工程模式（Pro） / Engineering Mode (Pro)
    - 描述：一次性解锁工程模式专家面板，永久可用
    - 价格：中国开发者账号默认币种 = **USD**，基准价建议 `USD 2.99` 起（约 ¥21），各区域自动换算本地币种 + 税；⚠️ 不要把 `¥18` 当基准价输入。**创建后必须点「激活」(Activate)**
    - 购买选项：ID 填 `buy`、交易类型 **Buy（购买）**、内容类型选「**数字内容**」；**只建 1 个购买选项**；向后兼容**不用管**（首个 Buy 由 Play 自动标记，billing 9.1.0 走新模型）。第 2 步逐字段见 `PLAY_PRODUCT_STEP2.md`；设价走右上 `Set prices → 批量修改价格`（`2.99` / USD）；最后**保存 → 启用**
    - 税务、法规遵循和计划：按页面引导完成；面向欧盟注意数字内容撤回权
    - （旧版界面才有的字段：Withdrawal Right Type = `DIGITAL_CONTENT`）
  - ⚠️ **不点「激活」= 用户点购买时什么都不会弹出**。这是最常见的踩坑点。
- 内购说明文案要点：一次性买断不是订阅；重装/换机可恢复；购买由 Google Play 处理。
- 国家/地区：默认全选，或按需（⚠️ 地区没开放是「SKU 查不到」的常见原因；应用未上架的地区用户也买不了）。
- 设备分类：手机 + 平板（应用支持横竖屏）。
- Managed Google Play（企业分发）：不需要可不勾。

---

## 4. 上传与提交
1. Play Console → 新建应用 → 填 1 节的基础信息。
2. **测试 → 内部测试（Internal testing）** 先跑一轮 → 上传 `RelicCam-v1.0.0-vc72.aab`（⚠️ vc47~vc71 全部作废：vc48 是 libz 补丁写错字节的坏包；vc49~vc68 是调色标定、布局修复与反馈入口合并的中间版；vc69 的三档直选弹窗被用户否决；vc70 缺 vivo 机身控件自适应；vc71 是内测候选。**上传用 vc72**（删除失效的「调试：跳过冲洗立刻出片」开关，含 vc71 全部修复，零新增权限））。
3. 依次补全第 2 节所有 App content 表单（标「待处理」的都要点完成）。
4. 填完 Store listing（第 1 节）并上传图标/特图/截图。
5. 提交审核。

---

## 4.5 每次版本迭代（升 versionCode）必做

⚠️ **GitHub Release 资产必须与 versionCode 同步**。App 的「检查更新」在侧载包上会跳
`https://github.com/steveJJJ/FilmCam/releases`；资产落后会让用户装回旧版 —— 2026-11-10
之前就发生过：Release 停在 vc49，而代码已到 vc65，落后 16 个版本。

固定 6 步，缺一不可：

1. `app/build.gradle.kts` 改 `versionCode`。
2. `export JAVA_HOME="$HOME/.jdks/jbr-21.0.11" && export MSYS_NO_PATHCONV=1` →
   `./gradlew testDebugUnitTest assembleRelease bundleRelease`。
3. 核验：`aapt2 dump badging` 版本号、6 个 `.so` 的 `p_align=16384`、libz 等长补丁在位、
   `aapt2 dump permissions` **仍无 INTERNET**（v65 起新增的依赖也不能把权限带回来）。
4. 归档两个包到 `play_console_upload/RelicCam-v1.0.0-vc<N>.{apk,aab}`。
5. **同步 GitHub Release（必做）**：
   ```bash
   python scripts/publish_github_release.py --check   # 只读，退出码 2 = 落后
   python scripts/publish_github_release.py           # 发布
   python scripts/publish_github_release.py --check   # 必须 STATUS: IN_SYNC
   ```
   脚本自动读版本号 → 建 `v1.0.0-vc<N>` Release（`make_latest=true`）→ 传 APK + AAB →
   给旧 Release 加废弃横幅 → 按 sha256 校验远端资产。`--dry-run` 只看计划，
   `--notes-extra` 追加说明，`--vc N` 补发历史版本，`--refresh-notes` 才覆盖已有正文。
   token 自动从 `GH_TOKEN` 或 Windows 凭据管理器读；代理默认 `127.0.0.1:7897`
   （`--no-proxy` 关闭）。**幂等**：同 tag 复用、sha256 相同跳过上传、
   不删任何旧资产、不动无资产的内测引导 Release。
6. `git commit` + `push`；同步本清单的版本引用（作废区间、上传用 vc<N>）。

Release 现状（2026-11-11）：`v1.0.0-vc72` 为 **Latest**（vc72 APK + AAB，
sha256 见 Release 正文），`v1.0.0-vc71` / `v1.0.0-vc70` / `v1.0.0`（vc49）等旧版已标废弃，无资产的 `RelicCam` 内测引导页保持不动。

vc72 核验记录：`aapt2 dump badging` versionCode=72 / targetSdk=36；6 个 `.so` 的
`PT_LOAD p_align=16384` 全通过、无 `libz.so.1` 残留；`aapt2 dump permissions` 与 vc71 一致（11 项）、仍无 INTERNET；单测 301 例 / 0 失败；
`assembleRelease` + `bundleRelease` BUILD SUCCESSFUL。

vc72 变更：删除工程师控制台里失效的「调试：跳过冲洗立刻出片」开关。该开关
`debugInstantDevelop` 只在 `MainActivity.onCreate` 读一次、`onResume` 不重读，且 `onPause`
的 `saveSettings()` 会把内存旧值回写覆盖控制台刚写的新值 → 打开后永远不生效、状态还会被抹掉。
按用户拍板整体移除：UI Switch、prefs key、拍照回调触发、中英两个字符串与相关注释；
`DevelopController.developRollInBackground` 保留（暗房「后台冲洗」按钮仍依赖它）。
新增 2 条源码契约单测防止死开关被加回来。产物对比：vc71 → vc72 的 60 个 `res/*.xml`
差异均为删字符串导致的资源 ID 重排（抽样文件长度不变、仅 11 字节差异），
字符串资源 383 → 382；APK 内 4 个死开关标识零残留。**冲洗链路、白边渲染、预览几何、
vivo 机身 UI 修复全部未动**。

vc71 变更（已并入 vc72）：修 vivo X300 Ultra 横屏 LCD 压预览框 35px、竖屏设置键被裁 30px
（`ChromeSafeArea.fitLcdWidth` 按可用空间反算，S25U 横竖屏逐像素零变化）；
诊断新增 `chrome.lcdWidthPx` / `lcdDesignPx` / `lcdClamped`。
定量报告见 `tmp/sample_1109/vivo_x300ultra_chrome_overlap_research.md`。

---

## 5. 待你确认的两件事
1. ~~**隐私政策 URL**~~ ✅ 已提供并核验：`https://stevejjj.github.io/relic-cam-privacy/`（已对照 release manifest 核实：无 INTERNET 权限、allowBackup=false、权限清单与政策逐字一致，Play 交叉核对不会打回）。
2. **商标**：当前应用内仍显示「CONTAX T3」字样（快门按钮上方、EXIF、部分文案）。上传前建议替换为自有命名，避免品牌异议。需要我批量改掉可随时说。
