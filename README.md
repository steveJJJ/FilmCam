# FilmCam MVP

胶片 / CCD 风格相机 App（Android 先行）+ FotorGear COB 蓝牙闪光灯支持。

## 项目结构

```
FilmCam/
├── settings.gradle.kts
├── build.gradle.kts
└── app/
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/filmcam/app/
        │   ├── MainActivity.kt          # 权限 + 音量键快门 + 整体调度
        │   ├── camera/
        │   │   └── CameraController.kt   # CameraX 取景
        │   ├── filter/
        │   │   ├── LutGLSurfaceView.kt   # OpenGL ES 滤镜渲染表面
        │   │   └── LutParser.kt          # .cube LUT 文件解析
        │   └── flash/
        │       ├── FlashDevice.kt         # 闪光灯抽象接口
        │       └── FotorGearFlash.kt      # FotorGear COB BLE 驱动（0xFFE1 透传通道）
        └── res/
            ├── layout/activity_main.xml
            └── raw/sample_lut            # 占位 identity LUT，替换为 spektrafilm 烘焙的 .cube
```

## MVP 核心链路

```
CameraX 取景 → LutGLSurfaceView（OpenGL 实时滤镜）→ 屏幕显示
按灯上物理键 → HID 模拟 Volume Down → MainActivity.onKeyDown() → 触发快门
App 屏幕快门按钮 → 触发快门（BLE 主动触发灯待逆向协议后启用）
```

## 已知硬件（FG-COB-FLASH）
- MAC: DA:BA:CD:E2:BE:B0
- 模块: HideLink（nRF52832），固件 2026.02.03
- 控制通道: Service 0xFFE0 / Characteristic 0xFFE1（WRITE NO RESPONSE + NOTIFY）
- HID 遥控: Report 0x2A4D，物理按键 = Volume Down

## 如何替换成真实胶片 LUT

1. 用 spektrafilm 离线烘焙 LUT（参考 `胶片CCD相机App_项目文档.md` 第 4.3 节）：
   ```bash
   spektrafilm-lut --film kodak_portra_400 --print kodak_portra_endura --out portra400.cube
   ```
2. 把生成的 .cube 文件复制到 `app/src/main/res/raw/portra400`（去掉扩展名）
3. 在 MainActivity 里改：`binding.glSurfaceView.loadLutFromRaw(R.raw.portra400)`

## 待完成（按优先级）

- [ ] OpenGL 着色器接入真实 3D LUT 查表（目前是直通占位）
- [ ] ImageCapture 用例接入（拍照片保存到相册）
- [ ] 胶片颗粒 / 漏光 / 日期水印叠加
- [ ] BLE 0xFFE1 协议逆向完成后填 FotorGearFlash.trigger()
- [ ] 内购系统（Google Play Billing）
