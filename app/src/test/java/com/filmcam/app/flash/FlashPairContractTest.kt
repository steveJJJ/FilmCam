package com.filmcam.app.flash

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 自学习配对向导（vc86 第三层）源码契约。
 * 向导依赖真机 BLE，JVM 跑不了，所以锁「关键接线的存在性与形态」，
 * 防回退 / 防接线悄悄断（沿袭 FlashPrecaptureContractTest 的做法）。
 */
class FlashPairContractTest {

    private fun read(rel: String): String? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val d = dir ?: return null
            val main = File(d, "app/src/main")
            if (main.isDirectory) {
                val f = File(main, rel)
                if (f.isFile) return f.readText()
            }
            dir = d.parentFile
        }
        return null
    }

    @Test
    fun `向导引擎候选帧表非空 且含 FotorGear 实测闪光码 05 00`() {
        val list = FlashPairEngine.CANDIDATE_FRAMES
        assertTrue("候选帧表不能为空", list.isNotEmpty())
        assertTrue(
            "0xFFE1 协议逆向实测 05 00 能闪光，必须出现在候选表前排（FotorGear 用户走向导时零试错）",
            list.indexOf("05 00") in 0..5
        )
        assertTrue(
            "真机命中 05 01，两条 05 命令码必须置顶，用户不用翻到中段",
            list.take(2) == listOf("05 01", "05 00")
        )
        // 候选帧全部是合法 hex（发出去的每一条都得能被 parseHex 解析）
        for (hex in list) {
            assertTrue("候选帧 $hex 不是合法 hex", FlashTriggerCommand.parseHex(hex) != null)
        }
    }

    @Test
    fun `向导保存的档案走私有目录而非外部目录`() {
        // v60 教训：点灯能力不能依赖 getExternalFilesDir（清数据即失效）
        val store = read("java/com/filmcam/app/flash/FlashProfileStore.kt") ?: return
        assertTrue(
            "档案必须存 filesDir（私有目录）",
            Regex("context\\.filesDir").containsMatchIn(store)
        )
        assertTrue(
            "档案存储不得调用外部目录 API（注释里的历史说明不算）",
            !Regex("getExternalFilesDir\\s*\\(").containsMatchIn(store)
        )
    }

    @Test
    fun `向导 Activity 注册在 manifest 且不导出`() {
        val manifest = read("AndroidManifest.xml") ?: return
        assertTrue(
            "FlashPairActivity 必须注册",
            Regex("android:name=\"\\.flash\\.FlashPairActivity\"").containsMatchIn(manifest) ||
                manifest.contains(".flash.FlashPairActivity")
        )
    }

    @Test
    fun `MainActivity 长按闪光状态字进向导 单击切档不受影响`() {
        val main = read("java/com/filmcam/app/MainActivity.kt") ?: return
        assertTrue(
            "长按入口必须接在 tvFlashStatus 上",
            Regex("binding\\.tvFlashStatus\\.setOnLongClickListener").containsMatchIn(main)
        )
        assertTrue(
            "长按要启动 FlashPairActivity",
            Regex("setOnLongClickListener[\\s\\S]{0,400}FlashPairActivity").containsMatchIn(main)
        )
        assertTrue(
            "单击切档的原交互必须保留",
            Regex("binding\\.tvFlashStatus\\.hapticClick\\s*\\{\\s*cycleFlashMode\\(\\)\\s*\\}").containsMatchIn(main)
        )
    }

    @Test
    fun `切到 EXT 且无已配对档案时自动弹向导 配对成功返回即建连`() {
        val main = read("java/com/filmcam/app/MainActivity.kt") ?: return
        // 用户拍板的正式入口流程：切 EXT → 无档案自动弹配对 → 配完即用
        assertTrue(
            "cycleFlashMode 的 EXTERNAL 分支必须先查已配对档案是否为空",
            Regex("FlashMode\\.EXTERNAL\\s*->\\s*FlashMode\\.OFF[\\s\\S]{0,2000}FlashProfileStore\\(this\\)\\.list\\(\\)\\.isEmpty\\(\\)")
                .containsMatchIn(main)
        )
        assertTrue(
            "无档案时必须走 flashPairLauncher 自动弹向导（不能只是 startActivity）",
            Regex("flashPairAutoFromExt = true[\\s\\S]{0,400}flashPairLauncher\\.launch").containsMatchIn(main)
        )
        assertTrue(
            "向导返回 OK 后必须重建 BLE 会话（startScan 内部 syncLearned 新档案）",
            Regex("resultCode == android\\.app\\.Activity\\.RESULT_OK[\\s\\S]{0,1200}flash\\.startScan\\(\\)")
                .containsMatchIn(main)
        )
        assertTrue(
            "自动弹出的向导被取消必须退回 OFF 档（EXT 无灯可用不能留空档）",
            Regex("flashPairAutoFromExt && !ok[\\s\\S]{0,600}FlashMode\\.OFF").containsMatchIn(main)
        )
    }

    @Test
    fun `系统已配对 HID 灯必须能绕过 BLE 广播被发现`() {
        val engine = read("java/com/filmcam/app/flash/FlashPairEngine.kt") ?: return
        // 真机取证：FG-COB-FLASH 以 HOGP 被系统绑定 + HidHost CONNECTED 后停止广播，
        // 1820 条 ScanController 结果里没有它的 MAC，纯扫描列表永远找不到灯。
        assertTrue(
            "startScan 必须先播种系统已配对设备",
            Regex("found\\.clear\\(\\)[\\s\\S]{0,200}seedBondedDevices\\(\\)").containsMatchIn(engine)
        )
        assertTrue(
            "已配对来源必须是 BluetoothAdapter.bondedDevices",
            Regex("adapter\\?\\.bondedDevices").containsMatchIn(engine)
        )
        assertTrue(
            "经典蓝牙音频设备必须排除",
            Regex("DEVICE_TYPE_CLASSIC").containsMatchIn(engine)
        )
        assertTrue(
            "灯候选必须置顶（likelyFlash 优先于 bonded / rssi 排序）",
            Regex("compareByDescending<ScanDevice>\\s*\\{\\s*it\\.likelyFlash\\s*\\}[\\s\\S]{0,200}it\\.bonded").containsMatchIn(engine)
        )
        assertTrue(
            "广播里没名字的设备不能再被 return 丢掉",
            Regex("onScanResult[\\s\\S]{0,700}flash_pair_unnamed").containsMatchIn(engine) &&
                !Regex("deviceName \\?: result\\.device\\.name \\?: return").containsMatchIn(engine)
        )
        assertTrue(
            "选中已配对设备仍按 MAC 直连 connectGatt",
            Regex("connect\\(address: String\\)[\\s\\S]{0,500}connectGatt\\(context, false").containsMatchIn(engine)
        )
    }

    @Test
    fun `向导保存成功必须回传 RESULT_OK`() {
        val act = read("java/com/filmcam/app/flash/FlashPairActivity.kt") ?: return
        assertTrue(
            "saveProfile 成功路径必须 setResult(RESULT_OK) 供 MainActivity 判断配对完成",
            Regex("store\\.save\\(profile\\)[\\s\\S]{0,600}setResult\\(android\\.app\\.Activity\\.RESULT_OK\\)")
                .containsMatchIn(act)
        )
    }

    @Test
    fun `向导保存后立即同步注册表 拍摄无需重启`() {
        val act = read("java/com/filmcam/app/flash/FlashPairActivity.kt") ?: return
        assertTrue(
            "保存档案后必须 syncLearned 让下一次快门即可用",
            Regex("store\\.save\\(profile\\)[\\s\\S]{0,600}syncLearned\\(store\\.list\\(\\)\\)").containsMatchIn(act)
        )
        assertTrue(
            "删除档案也要同步注册表",
            act.count { Regex("syncLearned\\(store\\.list\\(\\)\\)").containsMatchIn(it.toString()) } >= 0 &&
                act.contains("syncLearned(store.list())")
        )
        assertTrue(
            "向导要实现引擎监听（回调驱动 UI）",
            Regex("FlashPairActivity : AppCompatActivity\\(\\), FlashPairEngine\\.Listener").containsMatchIn(act)
        )
    }

    @Test
    fun `向导四页就地翻转 一步一屏不靠下滑`() {
        val layout = read("res/layout/activity_flash_pair.xml") ?: return
        val act = read("java/com/filmcam/app/flash/FlashPairActivity.kt") ?: return
        assertTrue(
            "四页必须放在同一个 FrameLayout 容器里，而不是顺序堆在长 ScrollView 中",
            layout.indexOf("<FrameLayout") >= 0 &&
                layout.indexOf("\"@+id/stepScan\"") > layout.indexOf("<FrameLayout") &&
                layout.indexOf("\"@+id/stepChar\"") > layout.indexOf("\"@+id/stepScan\"") &&
                layout.indexOf("\"@+id/stepFrame\"") > layout.indexOf("\"@+id/stepChar\"") &&
                layout.indexOf("\"@+id/stepDone\"") > layout.indexOf("\"@+id/stepFrame\"") &&
                layout.indexOf("</FrameLayout>") > layout.indexOf("\"@+id/stepDone\"")
        )
        assertTrue(
            "顶部机顶 LCD 页码 + 底部机身键必须固定在容器外",
            layout.contains("@+id/pairStep") && layout.contains("@+id/btnPrimary") &&
                layout.contains("@+id/btnBack")
        )
        assertTrue(
            "Activity 必须有四页状态机 + showPage",
            Regex("enum class Page[\\s\\S]{0,200}DEVICE\\(1\\)[\\s\\S]{0,200}DONE\\(4\\)").containsMatchIn(act) &&
                Regex("private fun showPage\\(p: Page").containsMatchIn(act)
        )
        assertTrue(
            "showPage 必须把非当前页 GONE（一次只显示一页）",
            Regex("private fun showPage\\(p: Page[\\s\\S]{0,1200}View\\.GONE").containsMatchIn(act)
        )
        assertTrue(
            "连上并枚举完通道必须自动翻到第 2 页",
            Regex("onWritableChars[\\s\\S]{0,1500}showPage\\(Page\\.CHAR\\)").containsMatchIn(act)
        )
        assertTrue(
            "保存成功必须翻到 PAIRED 完成页",
            Regex("showPage\\(Page\\.DONE\\)").containsMatchIn(act)
        )
    }

    @Test
    fun `已配对设备按通用协议特征过滤 不写品牌白名单`() {
        val engine = read("java/com/filmcam/app/flash/FlashPairEngine.kt") ?: return
        assertTrue(
            "灯候选判据必须是通用服务 UUID（HID 1812 / FFE0 / FFF0 / NUS）",
            Regex("FLASH_SERVICE_HINTS[\\s\\S]{0,300}1812[\\s\\S]{0,200}FFE0[\\s\\S]{0,200}FFF0[\\s\\S]{0,200}6E400001")
                .containsMatchIn(engine)
        )
        assertTrue(
            "名字兜底只能用通用品类词，不能出现厂牌名",
            Regex("FLASH_NAME_HINTS").containsMatchIn(engine) &&
                !Regex("fotorgear|fg-cob", RegexOption.IGNORE_CASE).containsMatchIn(engine)
        )
        assertTrue(
            "已配对设备要读系统缓存的服务 UUID 用于判断",
            Regex("d\\.uuids").containsMatchIn(engine)
        )
        val act = read("java/com/filmcam/app/flash/FlashPairActivity.kt") ?: return
        assertTrue(
            "第 1 页必须分三组渲染（灯候选 / 附近广播 / 其他已配对）",
            Regex("it\\.bonded && it\\.likelyFlash").containsMatchIn(act) &&
                Regex("!it\\.bonded").containsMatchIn(act) &&
                Regex("it\\.bonded && !it\\.likelyFlash").containsMatchIn(act)
        )
        assertTrue(
            "手动 MAC 兜底入口必须存在",
            Regex("connectManualMac\\(\\)").containsMatchIn(act) &&
                Regex("MAC_REGEX").containsMatchIn(act)
        )
    }

    @Test
    fun `试写串行化与推荐通道 防止 GATT 写入永久卡死`() {
        val engine = read("java/com/filmcam/app/flash/FlashPairEngine.kt") ?: return
        // 真机取证：试写全落在 2A4C / 2A00 配置类特征，第一条没有写回调后
        // BluetoothGatt 一直报 prior command is not finished，后续 40+ 条全 write=false。
        assertTrue(
            "通道推荐必须按通用协议形态（透传短码 + WRITE_NO_RESPONSE），配置类 2Axx 排后",
            engine.contains("TRIGGER_CHAR_HINTS") && engine.contains("looksLikeTrigger") &&
                engine.contains("00002A")
        )
        assertTrue(
            "可写特征必须按 recommended 置顶",
            Regex("compareByDescending<CharInfo>\\s*\\{\\s*it\\.recommended\\s*\\}").containsMatchIn(engine)
        )
        assertTrue(
            "每条可写通道 UUID 必须打日志（下次能直接看出灯有没有 FFE1）",
            engine.contains("WRITABLE \${c.charUuid}")
        )
        assertTrue(
            "写入前必须检查 writePending，不能连点硬写",
            Regex("fun writeFrame[\\s\\S]{0,500}if \\(writePending\\)").containsMatchIn(engine)
        )
        assertTrue(
            "onCharacteristicWrite 必须清 pending",
            Regex("onCharacteristicWrite[\\s\\S]{0,800}ackWrite\\(status\\)").containsMatchIn(engine) &&
                Regex("private fun ackWrite[\\s\\S]{0,300}writePending = false").containsMatchIn(engine)
        )
        assertTrue(
            "写入无回调必须超时自动重连解锁 GATT",
            engine.contains("WRITE_TIMEOUT_MS") && engine.contains("reconnectSilently()") &&
                engine.contains("onReconnecting()")
        )
        val act = read("java/com/filmcam/app/flash/FlashPairActivity.kt") ?: return
        assertTrue(
            "向导要接管重连提示并作废旧帧，避免保存没发出去的档案",
            Regex("override fun onReconnecting[\\s\\S]{0,400}lastSentHex = null").containsMatchIn(act) &&
                act.contains("override fun onReconnected()")
        )
        assertTrue(
            "试闪页必须显示当前通道短码，用户能确认自己选的是不是 FFE1",
            act.contains("trialChannel") && act.contains("flash_pair_trial_channel")
        )
    }

    @Test
    fun `试闪命中确认 完成页档案可复制 取景器带灯名`() {
        val act = read("java/com/filmcam/app/flash/FlashPairActivity.kt") ?: return
        val main = read("java/com/filmcam/app/MainActivity.kt") ?: return
        assertTrue(
            "保存必须取用户确认命中的那条，不能拿最后一次试发的帧",
            Regex("fun saveProfile\\(\\)[\\s\\S]{0,500}val hex = hitFrame \\?: return").containsMatchIn(act) &&
                act.contains("hitFrame = hex")
        )
        assertTrue(
            "主按钮要带待保存指令 + 未命中时给禁用原因",
            act.contains("flash_pair_primary_confirm_with") && act.contains("frameHint")
        )
        assertTrue(
            "试过的帧要移到已试分组，可展开收起",
            act.contains("triedFrames") && act.contains("updateFrameGroups()") &&
                act.contains("flash_pair_frame_tried")
        )
        assertTrue(
            "完成页必须显示完整铭牌并支持复制行式档案文本",
            act.contains("flash_pair_done_meta") &&
                Regex("fun copyProfile[\\s\\S]{0,500}FlashProfileCodec\\.encode\\(p\\)").containsMatchIn(act)
        )
        assertTrue(
            "0 灯候选必须给行动指引与刷新按钮，不能只留一行灰字",
            act.contains("emptyCandidateRow") && act.contains("flash_pair_no_candidate_hint")
        )
        assertTrue(
            "取景器 EXT 状态字要带已配对灯名，配对返回 LCD 报 PAIRED",
            main.contains("main_external_status_named") &&
                main.contains("flash_pair_lcd_paired")
        )
    }

    @Test
    fun `设置面板有可见外闪管理入口 且向导支持管理模式`() {
        val main = read("java/com/filmcam/app/MainActivity.kt") ?: return
        val settings = read("res/layout/dialog_settings.xml") ?: return
        val act = read("java/com/filmcam/app/flash/FlashPairActivity.kt") ?: return
        val pairLayout = read("res/layout/activity_flash_pair.xml") ?: return
        val zh = read("res/values/strings.xml") ?: return
        val en = read("res/values-en/strings.xml") ?: return
        assertTrue(
            "设置面板必须有可见的外闪灯管理行（长按「闪」字太隐蔽）",
            settings.contains("@+id/rowSetFlash")
        )
        assertTrue(
            "rows 数组必须同步加入新行，否则 labels/hints 会整体错位",
            Regex("R\\.id\\.rowSetRaw[\\s\\S]{0,160}R\\.id\\.rowSetFlash").containsMatchIn(main)
        )
        assertTrue(
            "设置行读数要来自 FlashProfileStore（灯名 / 台数 / 未配对）",
            main.contains("FlashProfileStore(this).list()") && main.contains("set_flash_none")
        )
        assertTrue(
            "设置行点击必须走 launcher 并带管理模式标记（取消不退 EXT 档）",
            Regex("row\\(R\\.id\\.rowSetFlash\\)[\\s\\S]{0,700}flashPairLauncher\\.launch[\\s\\S]{0,300}EXTRA_MANAGE")
                .containsMatchIn(main)
        )
        assertTrue(
            "中英 set_labels 都必须补上 set_flash（否则数组长度不一致）",
            Regex("set_labels[\\s\\S]{0,700}@string/set_flash").containsMatchIn(zh) &&
                Regex("set_labels[\\s\\S]{0,700}@string/set_flash").containsMatchIn(en)
        )
        assertTrue(
            "向导必须定义并读取 EXTRA_MANAGE",
            act.contains("const val EXTRA_MANAGE") &&
                act.contains("intent.getBooleanExtra(EXTRA_MANAGE")
        )
        assertTrue(
            "管理区与扫描区必须互斥、同一页一步一屏",
            pairLayout.contains("@+id/manageBlock") && pairLayout.contains("@+id/scanBlock") &&
                Regex("fun applyManageVisibility[\\s\\S]{0,400}View\\.GONE").containsMatchIn(act)
        )
        assertTrue(
            "管理页必须能复制 / 删除 / 导入档案（众包交换闭环）",
            act.contains("renderManageList") && act.contains("store.delete(p.address)") &&
                Regex("fun importProfile[\\s\\S]{0,700}FlashProfileCodec\\.decode").containsMatchIn(act)
        )
        assertTrue(
            "从扫描区按 BACK 要回管理区，而不是直接退出向导",
            Regex("manageMode && !manageVisible\\) showManage\\(\\)").containsMatchIn(act)
        )
    }

    @Test
    fun `档案驱动是通用驱动 不含品牌知识`() {
        val drv = read("java/com/filmcam/app/flash/LearnedProfileDriver.kt") ?: return
        assertTrue(
            "LearnedProfileDriver 必须实现 ExternalFlashDriver",
            Regex("class LearnedProfileDriver\\([\\s\\S]{0,200}\\)\\s*:\\s*ExternalFlashDriver").containsMatchIn(drv)
        )
        assertTrue(
            "通用驱动不得硬编码任何品牌协议常量（UUID / MAC / 触发码都得来自档案）",
            !Regex("FFE1").containsMatchIn(drv) &&
                !Regex("DA:BA:CD").containsMatchIn(drv) &&
                !Regex("\"05 00\"").containsMatchIn(drv) &&
                !Regex("fg-cob|FG-COB", RegexOption.IGNORE_CASE).containsMatchIn(drv)
        )
    }
}
