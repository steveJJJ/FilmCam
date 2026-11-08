# Relic Cam — Play Console 上传素材包

直接对应 Play Console 各字段，按文件名上传即可。

| 文件 | 尺寸 | Play Console 字段 |
|---|---|---|
| `icon_hires_512.png` | 512×512 | **App icon（高分图标）** |
| `feature_graphic_1024x500.png` | 1024×500 | **Feature graphic（特色图）** |
| `01_camera_1080x2160.png` | 1080×2160 (2:1) | **Phone screenshots（手机截图 1/3）— 取景器/拍摄** |
| `02_gallery_1080x2160.png` | 1080×2160 (2:1) | **Phone screenshots（手机截图 2/3）— 胶片相册** |
| `03_developed_photo_1080x2160.png` | 1080×2160 (2:1) | **Phone screenshots（手机截图 3/3）— 冲洗后的胶片照** |

## 说明
- 截图均来自真机（S25U）实拍，已处理成 Play 要求的 2:1 比例（黑边补足，UI 完整保留）。
- 上传应用包用工程里的 `app/build/outputs/bundle/release/app-release.aab`（已签名）。
- 表单逐字段填写指南见上一层 `PLAY_CONSOLE_CHECKLIST.md`。
