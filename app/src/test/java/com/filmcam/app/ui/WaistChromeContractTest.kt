package com.filmcam.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 腰平取景 UI 契约（MF-2 取景罩那一版）。
 *
 * 锁死两件事：
 *  1. **两个 layout 变体同名 id 必须同步** —— view binding 合并多变体时，
 *     只在一边出现的 id 会生成 nullable 属性，`binding.waistShutter` 直接编译错。
 *  2. **退出三通道与读数接线** —— CLOSE / 返回键 / 长按黑屏都必须收敛到
 *     setWaistLevel(false)，读数必须用真实 EV / 闪光 / 帧数，不能是静态文案。
 */
class WaistChromeContractTest {

    private fun read(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val d = dir ?: error("找不到 $rel")
            val main = File(d, "app/src/main")
            if (main.isDirectory) {
                val f = File(main, rel)
                if (f.isFile) return f.readText().replace("\r\n", "\n")
            }
            dir = d.parentFile
        }
        error("找不到 $rel")
    }

    private val waistIds = listOf(
        "waistMask", "waistTopBar", "waistScrewLeft", "waistTopLabel", "waistScrewRight",
        "waistFinderOverlay", "waistBar", "waistReadout", "waistClose", "waistRotate",
        "waistShutter",
    )

    @Test
    fun `竖屏与横屏布局的腰平 id 完全同步`() {
        val portrait = read("res/layout/activity_main.xml")
        val land = read("res/layout-land/activity_main.xml")
        waistIds.forEach { id ->
            assertTrue("竖屏缺 @id/$id", portrait.contains("android:id=\"@+id/$id\""))
            assertTrue("横屏缺 @id/$id（view binding 会变 nullable）", land.contains("android:id=\"@+id/$id\""))
        }
    }

    @Test
    fun `三个退出通道都收敛到 setWaistLevel`() {
        val main = read("java/com/filmcam/app/MainActivity.kt")
        assertTrue("CLOSE 没接退出", main.contains("waistClose.hapticClick { setWaistLevel(false) }"))
        assertTrue("长按黑屏没接退出", main.contains("waistMask.setOnLongClickListener"))
        assertTrue("返回键没有 OnBackPressedCallback", main.contains("OnBackPressedCallback"))
        assertTrue("返回键没调用 setWaistLevel(false)", main.contains("handleOnBackPressed() {\n                setWaistLevel(false)"))
        assertTrue("返回键没按腰平态开关", main.contains("waistBackCallback?.isEnabled = waistApplied"))
        // 设置面板那条可见开关已整行移除（总闸搬到工程模式）；三条退出通道仍收敛同一入口
        assertTrue("设置面板不该再有腰平行", !main.contains("row(R.id.rowSetWaist)"))
        assertTrue("CLOSE 仍要收敛到 setWaistLevel", main.contains("private fun setWaistLevel(enabled: Boolean)"))
    }

    @Test
    fun `前盖读数是真实参数不是静态文案`() {
        val main = read("java/com/filmcam/app/MainActivity.kt")
        val fn = main.substringAfter("private fun updateWaistReadout()").substringBefore("\n    }")
        assertTrue("读数没用 EXT 细档", fn.contains("flashCompEv"))
        assertTrue("读数没用常规 EV", fn.contains("exposureComp"))
        assertTrue("读数没接卷内帧数", fn.contains("currentShotCount()"))
        assertTrue("读数没接闪光档位", fn.contains("flash.mode"))
        assertTrue("updateLcd 没刷新腰平读数", main.contains("updateWaistReadout()"))
    }

    @Test
    fun `取景罩弹开动效存在且只在跳变时播`() {
        val chrome = read("java/com/filmcam/app/ui/WaistLevelChrome.kt")
        assertTrue("没有顶沿位移动效", chrome.contains("topBar.animate().translationY(0f)"))
        assertTrue("没有前盖位移动效", chrome.contains("bar.animate().translationY(0f)"))
        assertTrue("没有屏框淡入", chrome.contains("overlay.animate().alpha(1f)"))
        assertTrue("跳变判定丢了（会反复重播）", chrome.contains("waist && !lastWaist"))
        assertTrue("退出没归位", chrome.contains("resetTransforms"))
        // 真机复现：叠层 alpha 动画末帧被合成器丢掉，屏幕停在没画的中间帧
        assertTrue("alpha 动画没有末帧兜底", chrome.contains("withEndAction"))
        assertTrue("末帧没补 invalidate", chrome.contains("overlay.invalidate()"))
        assertTrue("没有稳态自愈分支", chrome.contains("else if (waist && !opening)"))
    }

    @Test
    fun `取景格与预览共用同一套几何`() {
        val overlay = read("java/com/filmcam/app/ui/WaistFinderOverlayView.kt")
        // 表面即画幅：取景格直接画在自己的 bounds 内缩一圈，不再另算内接矩形
        assertTrue("取景格没按 bounds 画", overlay.contains("canvas.drawRect(fl, ft, fr, fb, framePaint)"))
        assertTrue("覆盖层还在按旧 letterbox 换算", !overlay.contains("contentRectFor"))
        // 取景屏夹在顶沿与前盖之间：两段实心块高度都要扣掉，否则取景格下沿被盖子压住
        val chrome = read("java/com/filmcam/app/ui/WaistLevelChrome.kt")
        assertTrue("没扣前盖高度", chrome.contains("f.height - topH - barH"))
        assertTrue("没读前盖高度", chrome.contains("val barH = (bar.layoutParams?.height ?: 0)"))
        assertTrue("画幅矩形没按 posture.gateAspect 内接", chrome.contains("val ga = posture.gateAspect"))
        assertTrue("GL/取景框没铺画幅矩形", chrome.contains("lp.width = gateW.toInt()"))
    }

    @Test
    fun `画幅自动判定与 MF-2 独占绘制接线正确`() {
        val main = read("java/com/filmcam/app/MainActivity.kt")
        // 画幅档收敛到单一入口并落 prefs（自动 / 1:1 / 3:2 三档循环）
        assertTrue("waistRotate 没接画幅切换", main.contains("binding.waistRotate.hapticClick"))
        assertTrue("画幅切换没走 setWaistGateStyle", main.contains("setWaistGateStyle("))
        assertTrue("画幅档没持久化", main.contains("prefs.putString(\"waistGateStyle\""))
        assertTrue("画幅档没从 prefs 恢复", main.contains("prefs.getString(\"waistGateStyle\", \"auto\")"))
        assertTrue("求解器没接画幅档", main.contains("gateStyle = waistGateStyle"))
        // 预览不再拉伸：内容朝向跟 display rotation 走、腰平态裁切填充铺满表面
        assertTrue("没挂 waistFill", main.contains("binding.glSurfaceView.waistFill = waistApplied"))
        assertTrue("对焦没传画幅比", main.contains("waistGateAspect = if (waistApplied) waistGateAspect else 0f"))
        assertTrue("ShotMeta 没记画幅", main.contains("gate = if (waistApplied"))
        val gl = read("java/com/filmcam/app/filter/LutGLSurfaceView.kt")
        assertTrue("GL 没有 waistFill 开关", gl.contains("var waistFill: Boolean = false"))
        assertTrue("腰平内容朝向没跟 rotation 走", gl.contains("ROTATION_90, android.view.Surface.ROTATION_270 -> 4f / 3f"))
        assertTrue("腰平没有裁切填充分支", gl.contains("if (contentAspect > screenAspect) 1f else 0f"))
        // 腰平态 T3 标记必须整体不画，但不能影响对焦手势的几何
        val frame = read("java/com/filmcam/app/ui/ViewfinderFrameView.kt")
        assertTrue("ViewfinderFrameView 没有 waistSuppress", frame.contains("var waistSuppress = false"))
        assertTrue("waistSuppress 没在 onDraw 生效", frame.contains("if (waistSuppress) return"))
        assertTrue("MainActivity 没挂 waistSuppress", main.contains("binding.viewfinderFrame.waistSuppress = waistApplied"))
        assertTrue("腰平 contentRect 没放开到整个画幅", frame.contains("var waistGate = false"))
        assertTrue("waistSuppress 不得连 contentRect 一起藏", main.contains("binding.viewfinderFrame.contentRect()"))
    }

    @Test
    fun `画幅贯穿拍摄到成片全链路`() {
        val main = read("java/com/filmcam/app/MainActivity.kt")
        assertTrue("ShotMeta 没接 6x6 画幅", main.contains("FilmGate.MF6x6.key"))
        val meta = read("java/com/filmcam/app/film/ShotMeta.kt")
        assertTrue("ShotMeta 没有 gate 字段", meta.contains("val gate: String = \"135\""))
        assertTrue("gate 没落 JSON", meta.contains("put(\"gate\", gate)"))
        assertTrue("老帧没回落 135", meta.contains("o.optString(\"gate\", \"135\")"))
        val mapper = read("java/app/spektralab/engine/FilmStockMapper.kt")
        assertTrue("缺 120_6x6 引擎版式", mapper.contains("MF6x6(\"6x6\", \"120_6x6\", 1f)"))
        assertTrue("中画幅没换底片幅宽", mapper.contains("filmFormatMm: Float = 36f"))
        val dev = read("java/com/filmcam/app/develop/DevelopmentService.kt")
        assertTrue("冲洗没按帧取画幅", dev.contains("private fun frameGate(shotJson: String?)"))
        assertTrue("片门裁切没用逐帧比例", dev.contains("frameGateAspect(frameShotJson, bmp.width < bmp.height)"))
        assertTrue("没有 120 片框真机预检", dev.contains("private fun pickMediumFrame("))
        assertTrue("6x6 帧没走 120 参数", dev.contains("mf66FrameParamsJson != null"))
        assertTrue("白边没跟着画幅外扩", dev.contains("WhiteBorderRenderer.apply"))
        val proc = read("java/app/spektralab/engine/SpektraProcessor.kt")
        assertTrue("RAW 预裁不认 120_6x6", proc.contains("\"120_6x6\" -> 1f"))
        assertTrue("RAW 方画幅没偶对齐", proc.contains("val square = kotlin.math.abs(aspect - 1f) < 0.01f"))
        val lock = read("java/app/spektralab/engine/FrameBorderLock.kt")
        assertTrue("120 纸背会被 135 齿孔带校色误伤", lock.contains("format == \"135\" || format == \"135_half\""))
        // 11-18：日期改由引擎印（`date_imprint_*`），6×6 靠 filmFormatMm=56 让
        // 毫米制的字高/内缩在方画幅上自然变小，不再需要 App 侧的内容区矩形。
        val core = read("java/com/filmcam/app/develop/EditRenderCore.kt")
        assertTrue("6×6 日期没按 120 幅宽", core.contains("if (medium) 56f else 36f"))
        val imp = read("java/app/spektralab/engine/DateImprint.kt")
        assertTrue("日期参数没走引擎", imp.contains("date_imprint_active"))
    }

    @Test
    fun `腰平取景窗才有横滑色温读数，且只在滑动期间短时出现`() {
        val main = read("java/com/filmcam/app/MainActivity.kt")
        val overlay = read("java/com/filmcam/app/ui/WaistFinderOverlayView.kt")
        // 腰平态抑制整层 T3 标记 → 必须另开一路把读数写进 MF-2 取景格
        assertTrue("缺腰平 WB 读数入口", main.contains("private fun updateWaistWbReadout()"))
        assertTrue("覆盖层没有 wbReadout", overlay.contains("var wbReadout: String = \"\""))
        assertTrue("覆盖层没按读数绘制", overlay.contains("if (wbReadout.isNotEmpty())"))
        // 仅腰平 + wbVisible（横滑起手置位、抬手 900ms 清）；整屏/静止/退出全空
        assertTrue("WB 读数没有限定腰平态", main.contains("if (waistApplied && wbVisible) label else \"\""))
        assertTrue("换档没刷新读数", main.contains("updateViewfinderIndicators(lastAfState, lastExposureSec)\n        updateWaistWbReadout()"))
        assertTrue("抬手没收起读数", main.contains("wbVisible = false") &&
            main.contains("updateWaistWbReadout()   // 腰平取景窗读数同步收起"))
        assertTrue("退出腰平没清残留", main.contains("// 进/出腰平都重算一次：退出时清空短时读数"))
        // 整屏的 T3 WB 面板 / 机顶 LCD 必须原样不动（不做常驻、不新增第二个状态口）
        assertTrue("T3 层抑制被破坏了", main.contains("binding.viewfinderFrame.waistSuppress = waistApplied"))
        assertTrue("机顶 LCD 被塞了 WB 读数", !main.contains("lcd_notice_wb"))
    }

    @Test
    fun `标称色温按 AOSP 3A 文档，不是实测值`() {
        val main = read("java/com/filmcam/app/MainActivity.kt")
        assertTrue("没写数值出处", main.contains("camera3_3Amodes.html"))
        listOf(
            "null, \"AUTO\"", "2700, \"INCAND\"", "5000, \"FLUOR\"",
            "5500, \"DAYLIGHT\"", "6500, \"CLOUDY\"", "7500, \"SHADE\"",
            "15000, \"TWILIGHT\"",
        ).forEach { entry ->
            assertTrue("缺档位标称色温 $entry", main.contains(entry))
        }
        assertTrue("AUTO 档不该编 K 值", main.contains("preset.kelvin == null -> \"WB AUTO\""))
        assertTrue("读数没带 K 值", main.contains("\"WB ≈") && main.contains("K · "))
        assertTrue("读数没用档位字段", main.contains("preset.kelvin") && main.contains("preset.code"))
        val zh = read("res/values/strings.xml")
        val en = read("res/values-en/strings.xml")
        assertTrue("中文没说明是标称值", zh.contains("标称色温（≈K，非实测）"))
        assertTrue("英文没说明是标称值", en.contains("nominal colour temperature (≈K, not measured)"))
    }

    @Test
    fun `腰平新文案中英齐备`() {
        val zh = read("res/values/strings.xml")
        val en = read("res/values-en/strings.xml")
        listOf(
            "waist_level_engrave", "waist_level_close", "waist_level_readout",
            "waist_level_cd_shutter", "waist_level_cd_rotate",
            "lcd_notice_waist", "lcd_notice_waist_off",
        ).forEach { name ->
            assertTrue("中文缺 $name", zh.contains("name=\"$name\""))
            assertTrue("英文缺 $name", en.contains("name=\"$name\""))
        }
    }
}
