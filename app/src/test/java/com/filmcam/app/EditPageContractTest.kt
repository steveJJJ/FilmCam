package com.filmcam.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2 暗房编辑页的「体验一致 + 导出性能」契约（11-17 真机四问题修复）。
 *
 * 真机反馈：①胶片框档无预览 ②日期水印位置不对 ③导出慢 ④UI 不契合 + 状态栏压文案。
 * 这里钉住四条源码级修复，防回退：
 *  1. EditActivity 调 EdgeToEdge.setup —— 顶栏/底栏注入系统栏 padding
 *  2. 布局背景 = 暗房红光 drawable（与 DevelopmentActivity 同源），pill 选中态自绘
 *  3. 日期画在纯母片上（contentForAspect 用纯画面尺寸），与导出侧同基准；
 *     不再画在 padded 整图上
 *  4. 白边/无框档导出走 full.jpg 本地 fast-path，不每次重渲引擎
 */
class EditPageContractTest {

    private fun sourceRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val d = dir ?: return null
            val main = File(d, "app/src/main")
            if (main.isDirectory) return main
            dir = d.parentFile
        }
        return null
    }

    private fun read(rel: String): String? =
        sourceRoot()?.let { File(it, rel).takeIf { f -> f.isFile }?.readText() }

    @Test
    fun `编辑页接入边到边 顶栏底栏都注入系统栏安全区`() {
        val act = read("java/com/filmcam/app/develop/EditActivity.kt") ?: return
        assertTrue("必须调用 EdgeToEdge.setup", act.contains("EdgeToEdge.setup("))
        assertTrue("顶栏注入 padding", act.contains("topView = findViewById(R.id.editTopBar)"))
        assertTrue("底栏注入 padding", act.contains("bottomView = findViewById(R.id.editBottomBar)"))
    }


    /**
     * v21b：重渲实现从 EditExportService 抽到了 EditRenderCore（导出与预览同源）。
     * 契约断言按「两文件拼接」匹配 —— 否则每次搬代码都要改一堆测试路径。
     */
    private fun readExportOrCore(): String? {
        val svc = read("java/com/filmcam/app/develop/EditExportService.kt") ?: return null
        val core = read("java/com/filmcam/app/develop/EditRenderCore.kt") ?: ""
        return svc + "\n" + core
    }

    @Test
    fun `编辑页布局用暗房红光背景与自绘pill 不再裸系统控件`() {
        val xml = read("res/layout/activity_edit.xml") ?: return
        assertTrue("背景跟暗房同源", xml.contains("@drawable/bg_development_red"))
        assertTrue("pill 自绘选中态", xml.contains("@drawable/bg_edit_choice"))
        assertTrue("日期控件也用暗房红 pill（无裸 Switch）", xml.contains("rgEditDate"))
    }

    @Test
    fun `日期改由引擎印 预览与导出同源`() {
        val act = read("java/com/filmcam/app/develop/EditActivity.kt") ?: return
        // 11-18：日期不再用 Canvas 手绘（用户两轮反馈「越来越丑」），
        // 改为引擎原生 `date_imprint_*`，预览走 renderPreviewWithEngineDate 重渲。
        assertFalse(
            "编辑页不得再手绘日期",
            act.contains("DateStampRenderer.drawOnBitmap(")
        )
        assertTrue(
            "日期开启时走引擎重渲",
            act.contains("renderPreviewWithEngineDate(")
        )
        assertTrue(
            "预览与导出共用同一渲染核心",
            act.contains("EditRenderCore.renderPreview(")
        )
    }

    @Test
    fun `片边档有可感知预览 白边档复用真实渲染器`() {
        val act = read("java/com/filmcam/app/develop/EditActivity.kt") ?: return
        assertTrue("片边档本地齿孔示意", act.contains("drawFilmEdgePreview("))
        assertTrue("白边档复用 WhiteBorderRenderer", act.contains("FRAME_WHITE -> WhiteBorderRenderer.apply(src)"))
    }

    @Test
    fun `导出fast-path 白边无框档不重渲引擎 片边档才重渲`() {
        val svc = readExportOrCore() ?: return
        assertTrue("存在 fast-path 判定", svc.contains("val fastPath = !needsEngine"))
        assertTrue("fast-path 从 full.jpg 拷贝", svc.contains("MotherNegativeStore.fullFile(rollId, index)"))
        assertTrue("片边档仍走引擎重渲", svc.contains("val result = rerender(mother, paramsWithDate, tmpOut, fallbackJson = fallbackWithDate)"))
    }

    @Test
    fun `冲洗存全分辨率母片供fast-path使用`() {
        val dev = read("java/com/filmcam/app/develop/DevelopmentService.kt") ?: return
        assertTrue("landAsMother 存 full.jpg", dev.contains("MotherNegativeStore.saveFull(roll.rollId, index, tmpJpeg)"))
        val store = read("java/com/filmcam/app/film/MotherNegativeStore.kt") ?: return
        assertTrue("删除联动清 full.jpg", store.contains("fullFile(rollId, it).delete()"))
    }

    @Test
    fun `片边参数真跑验证后带框 版式被拒才退回无框`() {
        val svc = readExportOrCore() ?: return
        // setParams 预检不够（片框版式在 solve 渲染期才校验），必须小图真跑
        assertTrue("片框真跑验证", svc.contains("SpektraProcessor.probeRenderOk(applicationContext, framed, w, h)"))
        assertTrue("与冲洗同源构建参数", svc.contains("FilmStockMapper.buildParamsJson(") && svc.contains("extraEntries = entries"))
        assertTrue("真跑通过才带框", svc.contains("params = framed // 验证通过，用带片框参数"))
        assertTrue("被拒退回无框", svc.contains("本帧不带片框"))
    }

    @Test
    fun `编辑只能整卷全量 去掉仅此张选项`() {
        val act = read("java/com/filmcam/app/develop/EditActivity.kt") ?: return
        // 源码里不应再有 scope 单选/仅此张分支
        assertFalse("无 rgScope", act.contains("rgScope"))
        assertFalse("无仅此张分支", act.contains("listOf(currentIdx)"))
        assertTrue("改动应用到整卷", act.contains("EditsStore.applyTo(rollId, indices, edit)"))
        assertTrue("导出整卷", act.contains("EditExportService.start(this, rollId, indices.toIntArray())"))
        val xml = read("res/layout/activity_edit.xml") ?: return
        assertFalse("布局无仅此张按钮", xml.contains("rbScopeOne"))
    }

    @Test
    fun `完成广播无条件在finally发出 绝不让进度永久卡死`() {
        val svc = readExportOrCore() ?: return
        val run = svc.substringAfter("private fun runExport(", "")
            .substringBefore("private fun exportOne(", "")
        val fin = run.substringAfter("} finally {", "")
        assertTrue("广播在 finally 内", fin.contains("sendBroadcast("))
        assertTrue("显式包规避隐式广播限制", fin.contains("setPackage(packageName)"))
        assertTrue("广播带失败数", fin.contains("putExtra(EXTRA_FAILED, failed)"))
        val act = read("java/com/filmcam/app/develop/EditActivity.kt") ?: return
        assertTrue("接收端无条件解锁按钮", act.contains("btnSave.isEnabled = true"))
    }

    @Test
    fun `保存成功回画廊 失败才留编辑页`() {
        val act = read("java/com/filmcam/app/develop/EditActivity.kt") ?: return
        val rcv = act.substringAfter("private val exportDoneReceiver", "")
            .substringBefore("override fun onCreate", "")
        assertTrue("成功分支 finish 返回画廊", rcv.contains("dirty = false") && rcv.contains("finish()"))
        // finish 必须在 failed==0 分支，失败分支要留页重试
        val success = rcv.substringAfter("} else {", "")
        assertTrue("finish 在成功分支", success.contains("finish()"))
    }

    @Test
    fun `母片模式冲洗不注入片框参数 避免JPG四比三喂给三比二片框`() {
        val dev = read("java/com/filmcam/app/develop/DevelopmentService.kt") ?: return
        assertTrue(
            "frameNumber motherMode 归零",
            dev.contains("frameNumber = if (motherMode) 0 else if (perFrameNumber != 0) index else 0")
        )
        assertTrue(
            "frameParamsJson motherMode 置空",
            dev.contains("if (motherMode) null") && dev.contains("DateImprint.inject(frameParamsJson, frameDate)")
        )
        assertTrue("6x6 片框参数置空", dev.contains("mf66FrameParamsJson = null"))
    }

    @Test
    fun `导出重渲带兜底链 片框被拒时退回纯色彩不丢帧`() {
        val svc = readExportOrCore() ?: return
        // processFrame / processRawFrame 两路都必须传 fallbackParamsJsons
        assertTrue("processFrame 传兜底", svc.contains("recycleInput = true, fallbackParamsJsons = fallbacks"))
        assertTrue("processRawFrame 传兜底", svc.contains("fallbackParamsJsons = fallbacks,"))
        // 兜底链含母片纯色彩配方
        assertTrue("兜底用母片配方", svc.contains("mother.recipeJson.takeIf"))
        // 两处调用都注入 fallbackJson
        assertTrue("调用点传 fallbackJson", svc.contains("fallbackJson = fallbackWithDate") || svc.contains("fallbackJson = mother.recipeJson"))
    }

    @Test
    fun `全部帧导出后卷从暗房移除 部分导出则保留`() {
        val svc = readExportOrCore() ?: return
        assertTrue("判定全卷已导出", svc.contains("MotherNegativeStore.allExported(rollId)"))
        assertTrue("标记 developed", svc.contains("UndevelopedFilmStore.markDevelopedFromEdit(rollId)"))
        val store = read("java/com/filmcam/app/film/MotherNegativeStore.kt") ?: return
        assertTrue("allExported 要求每帧都有指纹", store.contains("recs.all { it.exportedFingerprints.isNotEmpty() }"))
        val ufs = read("java/com/filmcam/app/film/UndevelopedFilmStore.kt") ?: return
        assertTrue("只对 editable 卷生效", ufs.contains("if (roll.status != STATUS_EDITABLE) return false"))
    }

    @Test
    fun `编辑页支持左右滑动切帧 各帧独立编辑状态`() {
        val act = read("java/com/filmcam/app/develop/EditActivity.kt") ?: return
        assertTrue("触摸滑动切换", act.contains("switchFrame(if (dx < 0) 1 else -1)"))
        assertTrue("切帧加载对应状态", act.contains("loadEditState(currentIdx)"))
        assertTrue("加载时抑制监听器防误标脏", act.contains("loadingState = true"))
        assertTrue("监听器检查抑制标志", act.contains("if (loadingState) return@setOnCheckedChangeListener"))
        val xml = read("res/layout/activity_edit.xml") ?: return
        assertTrue("滑动提示", xml.contains("@string/edit_swipe_hint"))
    }

    @Test
    fun `导出JPEG帧先裁片门再进引擎 根治gate shape报错`() {
        val svc = readExportOrCore() ?: return
        // 真机 11-17 实锤：3060x4080 未裁 → 引擎报 not the 135 gate's shape
        assertTrue("导出侧做片门裁切", svc.contains("FilmStockMapper.gateCrop(oriented.width, oriented.height, aspect)"))
        assertTrue("按 shot.gate 判画幅", svc.contains("FilmStockMapper.FilmGate.fromKey(shot?.gate)"))
        assertTrue("裁后再送引擎", svc.substringAfter("val crop = FilmStockMapper.gateCrop").substringBefore("private fun rollInfoOf").contains("SpektraProcessor.processFrame("))
    }

    @Test
    fun `片边档跳过X Y验真避免齿孔被误判花屏导致重复重渲`() {
        val svc = readExportOrCore() ?: return
        assertTrue("只对非片边档验真", svc.contains("val needVerify = !EditsCodec.needsEngineRerender(edit.frame)"))
        assertTrue("验真受 needVerify 控制", svc.contains("val verdict = if (needVerify) BitmapVerifierAdapter.contrastVerdict"))
        assertTrue("片框探测缓存", svc.contains("frameProbeCache.getOrPut(probeKey)"))
    }

    @Test
    fun `界面不再显示应用到全部`() {
        val xml = read("res/layout/activity_edit.xml") ?: return
        assertFalse("无 scope 标签", xml.contains("edit_scope_label"))
        assertFalse("无仅此张/全部 pill", xml.contains("tvEditScopeAll") && xml.contains("rbScopeOne"))
    }

    @Test
    fun `冲洗预渲染带框成片 导出片边档秒取`() {
        val dev = read("java/com/filmcam/app/develop/DevelopmentService.kt") ?: return
        // 冲洗主循环在母片登记后调用预渲染
        assertTrue("冲洗循环调用预渲染", dev.contains("FramedPreRenderer.preRender(this, rollId, index)"))
        assertTrue("仅母片模式+开关开时预渲染", dev.contains("motherMode && com.filmcam.app.develop.DarkroomV2.isEnabled(this)"))
        val pre = read("java/com/filmcam/app/develop/FramedPreRenderer.kt") ?: return
        assertTrue("预渲染有真跑验证", pre.contains("SpektraProcessor.probeRenderOk(context, framed, w, h)"))
        assertTrue("预渲染用带框参数", pre.contains("extraEntries = entries"))
        assertTrue("预渲染同源 gate 裁切", pre.contains("FilmStockMapper.gateCrop"))
        assertTrue("预渲染走 RAW 通路", pre.contains("SpektraProcessor.processRawFrame"))
        assertTrue("预渲染走 JPEG 通路", pre.contains("SpektraProcessor.processFrame"))
        val svc = readExportOrCore() ?: return
        assertTrue("导出优先取预渲染图", svc.contains("useFramedPre = EditsCodec.needsEngineRerender(edit.frame)"))
        assertTrue("预渲染命中直接拷贝", svc.contains("framedPre.inputStream().use"))
        val store = read("java/com/filmcam/app/film/MotherNegativeStore.kt") ?: return
        assertTrue("存储层有 framed 文件", store.contains("fun framedFile"))
        assertTrue("删除联动清 framed", store.contains("framedFile(rollId, it).delete()"))
    }

    @Test
    fun `RAW竖拍方向后处理 预渲染与导出重渲都补旋转`() {
        val pre = read("java/com/filmcam/app/develop/FramedPreRenderer.kt") ?: return
        assertTrue("预渲染RAW后调方向修复", pre.contains("if (rawOk) orientRawOutput(context, mother.rollId, mother.index, out)"))
        assertTrue("方向修复按帧记录旋转", pre.contains("UndevelopedFilmStore.frameOrientation(rollId, index)"))
        assertTrue("竖拍检测", pre.contains("(rotation == 90 || rotation == 270) && bmp.width > bmp.height"))
        val svc = readExportOrCore() ?: return
        assertTrue("导出重渲回落也修方向", svc.contains("FramedPreRenderer.orientRawOutput("))
    }

    @Test
    fun `片边档预览显示真实预渲染带框图`() {
        val act = read("java/com/filmcam/app/develop/EditActivity.kt") ?: return
        assertTrue("预览读 framedFile", act.contains("MotherNegativeStore.framedFile(rollId, currentIdx)"))
        assertTrue("片边档优先用真实带框图", act.contains("frame == EditsCodec.FRAME_FILM && framedFile.exists()"))
        assertTrue("带框图缩略加载防OOM", act.contains("inSampleSize = sample"))
        assertTrue("假齿孔仅作老卷回落", act.contains("drawFilmEdgePreview(src)  // 老卷无 framed.jpg 的回落"))
    }

    @Test
    fun `RAW三处输出都接入裁后放大`() {
        val dev = read("java/com/filmcam/app/develop/DevelopmentService.kt") ?: return
        assertTrue("冲洗主通路回写前放大", dev.contains("RawUpscaler.upscale(bmp, shotJson)"))
        val pre = read("java/com/filmcam/app/develop/FramedPreRenderer.kt") ?: return
        assertTrue("预渲染后放大", pre.contains("RawUpscaler.upscaleFile(file, shotJson)"))
        assertTrue("横拍/已转向也放大(在旋转判定外)", pre.contains("裁后放大补回传感器尺寸（横拍/引擎已转好的帧也要放大"))
    }

    @Test
    fun `片边重渲复刻冲洗反ISP归一化 与母片同色`() {
        val dev = read("java/com/filmcam/app/develop/DevelopmentService.kt") ?: return
        assertTrue("回传normK槽", dev.contains("outNormK = frameNormK"))
        assertTrue("landAsMother存inputEv/normK", dev.contains("inputEv = inputEv, normK = outNormK?.get(0) ?: 0f"))
        assertTrue("归一化计算写回k", dev.contains("outNormK?.set(0, effectiveK)"))
        val pre = read("java/com/filmcam/app/develop/FramedPreRenderer.kt") ?: return
        assertTrue("预渲染复刻归一化", pre.contains("restoreJpegInput(oriented, mother.inputEv, mother.normK)"))
        assertTrue("复刻函数用JpegNormalizer", pre.contains("JpegNormalizer.apply(bmp, inputEv.toDouble(), normK)"))
        val svc = readExportOrCore() ?: return
        assertTrue("导出回落复刻归一化", svc.contains("FramedPreRenderer.restoreJpegInput(oriented, mother.inputEv, mother.normK)"))
        val reg = read("java/com/filmcam/app/film/MotherFilmRegistry.kt") ?: return
        assertTrue("母片存inputEv", reg.contains("val inputEv: Float"))
        assertTrue("母片存normK", reg.contains("val normK: Float"))
        assertTrue("序列化normK", reg.contains("o.put(\"normK\""))
    }

    @Test
    fun `日期开关点击后预览必须刷新且作废在途渲染`() {
        val act = read("java/com/filmcam/app/develop/EditActivity.kt") ?: return
        val render = act.substringAfter("private fun renderPreview()", "")
            .substringBefore("private fun renderPreviewWithEngineDate", "")

        // 🔴11-18 用户报「点日期开关没有预览」根因：作废代次只在「开日期」分支里 ++。
        // 关日期时不递增 → 之前那次带日期的异步渲染晚到，gen 仍相等，把带日期的图盖回来。
        assertTrue(
            "任何一次预览请求（含关日期）都必须先在入口作废在途渲染",
            render.contains("val gen = ++previewGen")
        )
        assertFalse(
            "作废代次不能只写在日期开启分支里",
            act.substringAfter("if (dateOn) {", "")
                .substringBefore("return", "")
                .contains("++previewGen")
        )
        assertTrue("开日期时把代次传下去", render.contains("renderPreviewWithEngineDate(frame, gen)"))

        val engine = act.substringAfter("private fun renderPreviewWithEngineDate", "")
            .substringBefore("private fun showPlainPreview", "")
        assertFalse("引擎预览不得自己再生成代次", engine.contains("++previewGen"))
        assertTrue("渲染期间必须有即时反馈", engine.contains("edit_date_rendering"))
        assertTrue("渲染完成后恢复档位提示", engine.contains("refreshHintText()"))
    }

    @Test
    fun `日期开关改成铭牌pill风格`() {
        val xml = read("res/layout/activity_edit.xml") ?: return
        assertTrue("日期用RadioGroup pill", xml.contains("rgEditDate"))
        assertTrue("有刻印pill", xml.contains("rbDateOn"))
        assertTrue("不刻pill", xml.contains("rbDateOff"))
        assertFalse("系统Switch已移除", xml.contains("swEditDate"))
        assertTrue("复用相框同款背景", xml.contains("@drawable/bg_edit_choice"))
    }

    @Test
    fun `暗房编辑v2全量默认开启 不再提供关闭入口`() {
        // 2026-11-18 用户拍板：V38 全链路真机验证完毕，暗房编辑 v2 转正，
        // 所有用户强制开，忽略历史 prefs 里手动关过的值。
        val v2 = read("java/com/filmcam/app/develop/DarkroomV2.kt") ?: return
        assertTrue("isEnabled 必须恒 true",
            v2.contains("fun isEnabled(context: Context): Boolean = true"))
        assertFalse("不得再读 SharedPreferences 决定开关", v2.contains("getBoolean(KEY_ENABLED"))
        assertFalse("不得留 setEnabled 让用户关回去", v2.contains("fun setEnabled("))

        val about = read("java/com/filmcam/app/AboutActivity.kt") ?: return
        assertFalse("控制台不得再翻转暗房开关（否则面板显示关、冲洗却走母片）",
            about.contains("DarkroomV2.setEnabled(this, on)"))
        assertTrue("控制台行只做状态展示",
            about.contains("tvDarkroomV2.text = getString(R.string.eng_darkroom_v2_on)"))

        // 通知文案同步：中英文都不得再写「需真机验证 / needs device test」
        val zh = read("res/values/strings_dev.xml") ?: return
        val en = read("res/values-en/strings_dev.xml") ?: return
        assertTrue("中文 hint 标注默认开启", zh.contains("默认开启：冲洗只出母片"))
        assertTrue("英文 hint 标注 On by default", en.contains("On by default: develop to mother only"))
        assertFalse("中文 hint 去掉需真机验证", zh.contains("需真机验证"))
        assertFalse("英文 hint 去掉 needs device test", en.contains("needs device test"))
    }
}
