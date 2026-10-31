# FilmCam

Android 胶片相机 App —— 光学取景器拍摄、整卷 36 张、暗房冲洗的真实胶卷工作流。
色彩由 Vulkan 引擎 **SpektraFilm** 按真实胶片 profile 渲染。引擎源自
[steveJJJ/SpektraLab-Android](https://github.com/steveJJJ/SpektraLab-Android)（Android Vulkan 移植，GPL-3.0-or-later）→
上游 [JamesQiu2005/SpektraLab](https://github.com/JamesQiu2005/SpektraLab)；胶片模型与实测配置来自
[Andrea Volpato 的 spektrafilm](https://github.com/andreavolpato/spektrafilm)（CC BY-SA 4.0）。

## 功能

- **25 支真实胶卷**：Kodak Portra / Ektar / Gold / Tri-X / T-Max、富士 Superia / C200 / Pro 400H / Acros、CineStill 800T、Vision3 电影卷等，与引擎 profile 一一对应
- **反转片直扫**：Provia / Velvia / Ektachrome / Kodachrome 走 `scan_film` 正片直出，不印相纸
- **胶卷工作流**：开卷 → 拍摄 36 张（中途不可回看，胶片机体验）→ 暗房整卷冲洗 → 相册
- **暗房可调**：颗粒 / 辉光 / 眩光强度、放大机曝光、相纸更换（按卷记忆）
- **日期角标**：可开关的胶片日期戳水印
- **外接闪光灯**：FotorGear COB 蓝牙闪光灯（BLE，协议逆向中）
- **完全离线**：无 INTERNET 权限，拍摄数据不出设备

## 工作流

```
取景（Camera2 + SurfaceTexture）→ 按快门（音量键 / 屏幕）→ 原始 JPEG 存入待冲洗卷
  → 冲洗（Vulkan 引擎按 film_stock + print_stock 调色、加颗粒/辉光/眩光）
  → 成片写入 DCIM/FilmCam（MediaStore，EXIF 含日期）→ 相册浏览
```

预览是光学取景器（无实时滤镜）——正如胶片机按下快门前看不到成片效果，冲洗后才有惊喜。

## 构建

要求：JDK 17+（已在 JDK 21 验证）、Android SDK 34。

```bash
./gradlew assembleRelease        # 产物 app/build/outputs/apk/release/
./gradlew testDebugUnitTest      # 25 个单元测试（映射一致性 / 参数契约 / 状态机）
```

签名：`keystore.properties` + `keystore/filmcam-release.jks`（均已 gitignore，请自行备份）。

## 项目结构

```
app/src/main/java/
├── com.filmcam.app/
│   ├── MainActivity.kt            # 取景、拍摄调度、胶卷/参数 UI
│   ├── camera/Camera2Controller.kt
│   ├── filter/                    # 胶卷列表（FilmRollRepository）、取景渲染
│   ├── develop/                   # 暗房：DevelopmentService（前台服务逐帧冲洗）
│   ├── film/UndevelopedFilmStore.kt  # 卷登记 + MediaStore 双路径存储（API26/29+）
│   └── flash/                     # FotorGear BLE 闪光灯驱动
├── app.spektralab.engine/
│   ├── FilmStockMapper.kt         # 胶卷名 → 引擎 film/print stock 冻结映射
│   └── SpektraProcessor 等        # Vulkan 引擎封装（JNI）
└── tools/                         # 图标/封面生成脚本（Node + CDP / Java2D）
```

资源：`app/src/main/assets/resources/` 为 SpektraFilm 引擎数据（25 film profiles、9 print profiles、stock_catalog 等），**许可证见 LICENSE-SPEKTRAFILM.txt**；项目代码为 GPL-3.0-or-later，第三方组件署名见 LICENSE 末尾的 Third-party 说明。

## 已知硬件（FG-COB-FLASH）

- MAC: DA:BA:CD:E2:BE:B0
- 模块: HideLink（nRF52832），固件 2026.02.03
- 控制通道: Service 0xFFE0 / Characteristic 0xFFE1（WRITE NO RESPONSE + NOTIFY）
- HID 遥控: Report 0x2A4D，物理按键 = Volume Down

## 开发约定

- `FilmStockMapper.ROLL_TABLE` 是 UI 胶卷名与引擎 stock id 的**冻结映射**：改任何一支必须同步 `FilmRollRepository`、`stock_catalog.json`，并跑单元测试（`FilmStockMapperTest` 会做三方一致性校验）
- 胶卷封面由 `tools/genfilmcover.js` 生成（Chrome headless + filmcover 版式），一览图由 `tools/GenSheet.java` 拼接，启动图标由 `tools/GenLauncherIcon.java` 生成
