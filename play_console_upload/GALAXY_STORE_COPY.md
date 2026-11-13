# Galaxy Store 表单文案（补上一轮被打断的截图）

> 对应页面：Galaxy Store → 输入应用程序基本信息 → 简体中文（默认）。
> 已填好：**Agreement ✅ 已勾**、**应用名称 `Relic Cam` ✅（9/150 byte）**。
> 还差：**应用程序介绍 \***（0/12000 byte）、**一句话简介 \***。

⚠️ 页面两条提示要一起满足：「请用所选语言输入说明、功能、**系统要求和支持语言**」+「**没有用英文填写，审核时有可能被拒**」。
所以下面中文文案里**内嵌了一段英文概要**；若想更稳，点顶部「**添加语言**」再加一份 en-US 完整英文版（见文末）。
单位按 **byte** 计，中文 UTF-8 约 3 byte/字，12000 byte 非常宽裕。

---

## 一句话简介 \*（直接复制）

```
把手机变成一台复古胶片相机，拍完送进暗房冲洗。
```

（约 66 byte，适合短字段；若还有字数限制，最短版：`复古胶片相机，拍完进暗房冲洗。`）

---

## 应用程序介绍 \*（直接复制）

```
Relic Cam 把你的手机变成一台复古胶片相机 —— 免费下载，工程模式（Pro）一次解锁、永久可用。

取景器里看到的是真实的光学世界；快门按下后一卷 36 张，拍完送进暗房冲洗，成片由胶片引擎按这一卷的配方显影。冲洗之前，谁也不知道拍到了什么 —— 这就是胶片的乐趣。

主要功能
- 真实胶片模拟：内置多款经典胶片色彩，拍摄即带胶片味道。
- 暗房冲洗：在手机里完成「冲卷」，反差、颗粒、色调由冲洗参数决定。
- 胶片质感：成片可带胶片齿孔、片基、日期戳与白边相框。
- 工程模式（Pro）：面向发烧友的专家面板，手工校准输入曝光、放大机曝光、乳剂与印相层参数。
- 全本地处理：拍摄与冲洗都在设备上完成，照片不会上传到任何服务器，无需联网。
- 简洁取景器：专注构图与曝光，没有花哨的滤镜商城。

系统要求与支持语言
- Android 8.0（API 26）及以上；需要相机权限；支持竖屏与横屏。
- 界面语言：简体中文、English。
- 免费下载；工程模式（Pro）为一次性内购，购买后永久可用，重装或换机可用同一账号恢复。

English summary: Relic Cam turns your phone into a vintage film camera. Shoot a 36-exposure roll, then develop it in the on-device darkroom with authentic film colours, grain, sprocket holes and date stamps. All processing is local and offline; no photos are uploaded. Free to download, with Engineering Mode (Pro) as a one-time unlock. Requires Android 8.0 (API 26) or later and camera permission. UI languages: Simplified Chinese, English.

免费下载。拿起手机，回到胶片时代。
```

---

## 若加 en-US 语言，用这份完整英文版

```
Relic Cam turns your phone into a vintage film camera — free to download, with Engineering Mode (Pro) as a single one-time unlock you keep forever.

- Authentic film looks powered by real film profiles.
- In-app darkroom: develop your roll on-device with controllable contrast, grain, and tone.
- Film aesthetics: sprocket holes, film rebate, date stamps and an optional white border.
- Engineering Mode (Pro): an expert panel for enthusiasts — hand-tune input exposure, enlarger exposure, and emulsion / printing-layer parameters.
- Fully on-device: capture and develop locally; your photos are never uploaded, and no internet connection is needed.
- A clean viewfinder built for composition and exposure.

System requirements & languages
- Android 8.0 (API 26) or later; camera permission required; portrait and landscape supported.
- Interface languages: Simplified Chinese, English.
- Free to download; Engineering Mode (Pro) is a one-time purchase, permanently yours and restorable with the same account.

Free to download. Pick up your phone and step back into the film era.
```

**English one-line summary:** `A vintage film camera in your pocket — shoot a roll, then develop it in the darkroom.`

---

## 三点别写错

1. 不要写「完全免费 / 无任何内购」—— 有 `pro_unlock` 一次性内购，口径必须是「免费下载 + 一次性解锁」。
2. 不要写「云端 AI 增强 / 需联网」—— App 权限里没有 INTERNET，全本地处理是真实卖点。
3. 隐私政策 URL 与 Google Play 保持一致：`https://stevejjj.github.io/relic-cam-privacy/`。

> 文案与 `PLAY_CONSOLE_CHECKLIST.md` 第 1 节的 Google Play 商店描述同源，只补了 Galaxy Store 明确要求的「系统要求与支持语言」和英文概要。
