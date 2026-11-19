package com.filmcam.app.flash

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.filmcam.app.R
import com.filmcam.app.ui.Haptics
import com.filmcam.app.ui.hapticClick
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 外闪自学习配对向导（通用外闪第三层）。
 *
 * 四页「就地翻转」，机身铭牌 / 机顶 LCD 纪律：顶部页码+状态、底部机身键固定不动，
 * 只有中间 pageContainer 换页 —— 不新开 Activity、不叠系统弹窗、不靠下滑找下一步。
 *  01 认灯：灯候选 / 附近广播 / 高级区（其他已配对 + 手动 MAC）
 *  02 通道：推荐透传口置顶，配置类特征隔开
 *  03 试闪：未试 / 已试分组，每行「闪了」命中确认，主按钮带待保存指令
 *  04 完成：完整铭牌 + 复制档案（行式文本可贴给朋友 / 论坛）
 */
class FlashPairActivity : AppCompatActivity(), FlashPairEngine.Listener {

    private enum class Page(val no: Int) { DEVICE(1), CHAR(2), FRAME(3), DONE(4) }

    private lateinit var engine: FlashPairEngine
    private lateinit var store: FlashProfileStore

    private lateinit var pairStep: TextView
    private lateinit var pairStatus: TextView
    private lateinit var pairTitle: TextView
    private lateinit var pairSubtitle: TextView
    private lateinit var manageBlock: LinearLayout
    private lateinit var scanBlock: LinearLayout
    private lateinit var manageHeader: TextView
    private lateinit var manageList: LinearLayout
    private lateinit var manageEmpty: TextView
    private lateinit var stepScan: LinearLayout
    private lateinit var stepChar: LinearLayout
    private lateinit var stepFrame: LinearLayout
    private lateinit var stepDone: LinearLayout
    private lateinit var pairedHeader: TextView
    private lateinit var nearbyHeader: TextView
    private lateinit var otherHeader: TextView
    private lateinit var advancedHeader: TextView
    private lateinit var advancedBlock: LinearLayout
    private lateinit var pairedList: LinearLayout
    private lateinit var deviceList: LinearLayout
    private lateinit var otherList: LinearLayout
    private lateinit var charList: LinearLayout
    private lateinit var frameList: LinearLayout
    private lateinit var triedList: LinearLayout
    private lateinit var untestedHeader: TextView
    private lateinit var triedHeader: TextView
    private lateinit var frameHint: TextView
    private lateinit var savedList: LinearLayout
    private lateinit var savedEmpty: TextView
    private lateinit var connectedMeta: TextView
    private lateinit var trialChannel: TextView
    private lateinit var manualHex: EditText
    private lateinit var manualMac: EditText
    private lateinit var btnPrimary: Button
    private lateinit var btnBack: Button
    private lateinit var doneText: TextView
    private lateinit var doneMeta: TextView

    private var page = Page.DEVICE
    /** 从设置面板「外闪灯管理」进入：第一屏给已配对档案，而不是认灯扫描。 */
    private var manageMode = false
    /** 第 1 页当前展示管理区还是扫描区（两者互斥，一步一屏）。 */
    private var manageVisible = false
    private var selectedDevice: FlashPairEngine.ScanDevice? = null
    private var selectedChar: FlashPairEngine.CharInfo? = null
    private var lastSentHex: String? = null
    private var savedProfile: FlashProfile? = null

    /** 03 页：行视图缓存 / 已试集合 / 用户确认命中的那条指令。 */
    private val frameRows = linkedMapOf<String, View>()
    private val triedFrames = linkedSetOf<String>()
    private var hitFrame: String? = null
    private var triedExpanded = false
    private var advancedExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 取证：若这里在配对中途又打一次，说明 Activity 被重建（配置变化/内存回收），
        // 那「跳回认灯」就不是导航 bug，而是状态没恢复。
        android.util.Log.w("FlashPairNav", "onCreate nullState=${savedInstanceState == null}")
        setContentView(R.layout.activity_flash_pair)
        engine = FlashPairEngine(this).also { it.listener = this }
        store = FlashProfileStore(this)

        pairStep = findViewById(R.id.pairStep)
        pairStatus = findViewById(R.id.pairStatus)
        pairTitle = findViewById(R.id.pairTitle)
        pairSubtitle = findViewById(R.id.pairSubtitle)
        manageBlock = findViewById(R.id.manageBlock)
        scanBlock = findViewById(R.id.scanBlock)
        manageHeader = findViewById(R.id.manageHeader)
        manageList = findViewById(R.id.manageList)
        manageEmpty = findViewById(R.id.manageEmpty)
        stepScan = findViewById(R.id.stepScan)
        stepChar = findViewById(R.id.stepChar)
        stepFrame = findViewById(R.id.stepFrame)
        stepDone = findViewById(R.id.stepDone)
        pairedHeader = findViewById(R.id.pairedHeader)
        nearbyHeader = findViewById(R.id.nearbyHeader)
        otherHeader = findViewById(R.id.otherHeader)
        advancedHeader = findViewById(R.id.advancedHeader)
        advancedBlock = findViewById(R.id.advancedBlock)
        pairedList = findViewById(R.id.pairedList)
        deviceList = findViewById(R.id.deviceList)
        otherList = findViewById(R.id.otherList)
        charList = findViewById(R.id.charList)
        frameList = findViewById(R.id.frameList)
        triedList = findViewById(R.id.triedList)
        untestedHeader = findViewById(R.id.untestedHeader)
        triedHeader = findViewById(R.id.triedHeader)
        frameHint = findViewById(R.id.frameHint)
        savedList = findViewById(R.id.savedList)
        savedEmpty = findViewById(R.id.savedEmpty)
        connectedMeta = findViewById(R.id.connectedMeta)
        trialChannel = findViewById(R.id.trialChannel)
        manualHex = findViewById(R.id.manualHex)
        manualMac = findViewById(R.id.manualMac)
        btnPrimary = findViewById(R.id.btnPrimary)
        btnBack = findViewById(R.id.btnBack)
        doneText = findViewById(R.id.doneText)
        doneMeta = findViewById(R.id.doneMeta)

        findViewById<Button>(R.id.btnManualMac).hapticClick { connectManualMac() }
        findViewById<Button>(R.id.btnManualSend).hapticClick { sendManual() }
        findViewById<Button>(R.id.btnNoneFlash).hapticClick { backToCharStep() }
        findViewById<Button>(R.id.btnTestAgain).hapticClick { testSavedTrigger() }
        findViewById<Button>(R.id.btnCopyProfile).hapticClick { copyProfile() }
        findViewById<Button>(R.id.btnImportProfile).hapticClick { importProfile() }
        btnBack.hapticClick { handleBack() }
        btnPrimary.hapticClick { handlePrimary() }

        advancedHeader.setOnClickListener {
            advancedExpanded = !advancedExpanded
            advancedBlock.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
            advancedHeader.text = getString(
                if (advancedExpanded) R.string.flash_pair_advanced_open
                else R.string.flash_pair_advanced
            )
        }
        triedHeader.setOnClickListener {
            triedExpanded = !triedExpanded
            updateFrameGroups()
        }

        manualHex.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                lastSentHex = null
                hitFrame = null
                updatePrimary()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        manageMode = intent.getBooleanExtra(EXTRA_MANAGE, false)
        manageVisible = manageMode
        if (manageMode) {
            pairTitle.setText(R.string.flash_pair_manage_title)
            pairSubtitle.setText(R.string.flash_pair_manage_subtitle)
        }
        applyManageVisibility()
        refreshSavedList()
        showPage(Page.DEVICE, animate = false)
        // 管理模式先看档案，不空扫 12 秒；点「配对新灯」时才开始认灯。
        if (!manageMode) restartScan()
    }

    override fun onDestroy() {
        engine.close()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java", ReplaceWith("handleBack()"))
    override fun onBackPressed() {
        handleBack()
    }

    // ---- 页状态机：任一时刻只有一页可见 ----

    private fun showPage(p: Page, animate: Boolean = true) {
        val from = page
        page = p
        // 取证：页间跳转全留痕（release 会裁 Log.d，这里必须用 Log.w）
        android.util.Log.w("FlashPairNav", "showPage ${from.no} -> ${p.no}")
        val pages = listOf(
            Page.DEVICE to stepScan,
            Page.CHAR to stepChar,
            Page.FRAME to stepFrame,
            Page.DONE to stepDone
        )
        for ((k, v) in pages) {
            if (k == p) {
                if (v.visibility != View.VISIBLE) {
                    v.visibility = View.VISIBLE
                    if (animate) {
                        v.alpha = 0f
                        v.animate().alpha(1f).setDuration(120).start()
                    } else v.alpha = 1f
                }
            } else {
                v.visibility = View.GONE
            }
        }
        pairStep.text = String.format("%02d / 04", p.no)
        btnBack.text = getString(
            when {
                p == Page.DEVICE && manageMode && !manageVisible -> R.string.flash_pair_back
                p == Page.DEVICE -> R.string.flash_pair_exit
                else -> R.string.flash_pair_back
            }
        )
        status(
            getString(
                when {
                    p == Page.DEVICE && manageVisible -> R.string.flash_pair_status_manage
                    p == Page.DEVICE -> R.string.flash_pair_status_scan
                    p == Page.CHAR -> R.string.flash_pair_status_connected
                    p == Page.FRAME -> R.string.flash_pair_status_trial
                    else -> R.string.flash_pair_status_paired
                }
            )
        )
        updatePrimary()
    }

    private fun updatePrimary() {
        btnPrimary.text = when (page) {
            Page.DEVICE -> getString(
                if (manageVisible) R.string.flash_pair_primary_pair_new
                else R.string.flash_pair_primary_rescan
            )
            Page.CHAR -> getString(R.string.flash_pair_primary_relight)
            Page.FRAME -> hitFrame?.let { getString(R.string.flash_pair_primary_confirm_with, it) }
                ?: getString(R.string.flash_pair_primary_confirm)
            Page.DONE -> getString(R.string.flash_pair_primary_finish)
        }
        val enabled = page != Page.FRAME || hitFrame != null
        btnPrimary.isEnabled = enabled
        btnPrimary.alpha = if (enabled) 1f else 0.4f
        frameHint.visibility = if (page == Page.FRAME && hitFrame == null) View.VISIBLE else View.GONE
    }

    private fun handlePrimary() {
        android.util.Log.w("FlashPairNav", "primary on page ${page.no}")
        when (page) {
            Page.DEVICE -> if (manageVisible) switchToScan() else restartScan()
            Page.CHAR -> backToDevice()
            Page.FRAME -> saveProfile()
            Page.DONE -> finish()
        }
    }

    /** 机身 BACK 键 / 系统返回键：一页一页退，不直接把用户甩出向导。 */
    private fun handleBack() {
        android.util.Log.w("FlashPairNav", "back on page ${page.no}")
        when (page) {
            Page.DEVICE -> if (manageMode && !manageVisible) showManage() else finish()
            Page.CHAR -> backToDevice()
            Page.FRAME -> showPage(Page.CHAR)
            Page.DONE -> finish()
        }
    }

    private fun backToDevice() {
        // 打全调用栈：这一步是「点通道却退回认灯」的唯一入口，必须看清是谁调的
        android.util.Log.w("FlashPairNav", "backToDevice (page ${page.no})", Throwable("nav-caller"))
        engine.disconnect()
        selectedDevice = null
        selectedChar = null
        clearFrameState()
        showPage(Page.DEVICE)
        restartScan()
    }

    private fun clearFrameState() {
        lastSentHex = null
        hitFrame = null
        triedFrames.clear()
        frameRows.clear()
        triedExpanded = false
        frameList.removeAllViews()
        triedList.removeAllViews()
    }

    private fun restartScan() {
        engine.disconnect()
        selectedDevice = null
        selectedChar = null
        savedProfile = null
        clearFrameState()
        pairedList.removeAllViews()
        deviceList.removeAllViews()
        otherList.removeAllViews()
        charList.removeAllViews()
        otherHeader.visibility = View.GONE
        if (page != Page.DEVICE) showPage(Page.DEVICE)
        status(getString(R.string.flash_pair_status_scan))
        engine.startScan()
    }

    // ---- 管理模式：设置面板「外闪灯管理」进来的第一屏 ----

    /** 管理区与扫描区互斥，同一页内切换，不靠下滑也不新开 Activity。 */
    private fun applyManageVisibility() {
        manageBlock.visibility = if (manageVisible) View.VISIBLE else View.GONE
        scanBlock.visibility = if (manageVisible) View.GONE else View.VISIBLE
    }

    /** 主按钮「＋ 配对新灯」：收起档案，回到常规四步配对流程。 */
    private fun switchToScan() {
        manageVisible = false
        applyManageVisibility()
        pairTitle.setText(R.string.flash_pair_title)
        pairSubtitle.setText(R.string.flash_pair_subtitle)
        restartScan()
        showPage(Page.DEVICE)
    }

    /** 从扫描区按 BACK：回档案管理，而不是直接退出向导。 */
    private fun showManage() {
        engine.disconnect()
        manageVisible = true
        applyManageVisibility()
        pairTitle.setText(R.string.flash_pair_manage_title)
        pairSubtitle.setText(R.string.flash_pair_manage_subtitle)
        refreshSavedList()
        showPage(Page.DEVICE)
    }

    private fun renderManageList(list: List<FlashProfile>) {
        manageList.removeAllViews()
        manageHeader.text = getString(R.string.flash_pair_manage_header, list.size)
        manageEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        for (p in list) manageList.addView(manageRow(p))
    }

    /** 管理行：灯名 / MAC / 通道 / 触发指令 + 复制 / 删除。 */
    private fun manageRow(p: FlashProfile): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        row.addView(TextView(this).apply {
            text = p.displayName
            setTextColor(0xFF9FE1CB.toInt())
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
        })
        row.addView(TextView(this).apply {
            text = getString(
                R.string.flash_pair_manage_row_meta,
                p.address,
                p.writeCharUuid.uppercase().removePrefix("0000").take(4),
                p.triggerHex
            )
            setTextColor(0xFF8A8880.toInt())
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(actionButton(getString(R.string.flash_pair_copy_short), 0xFFE8E6E1.toInt()) {
            copyProfile(p)
        })
        actions.addView(actionButton(getString(R.string.flash_pair_delete), 0xFFF0997B.toInt()) {
            store.delete(p.address)
            ExternalFlashRegistry.syncLearned(store.list())
            refreshSavedList()
            Toast.makeText(
                this, getString(R.string.flash_pair_manage_deleted, p.displayName),
                Toast.LENGTH_SHORT
            ).show()
        })
        row.addView(actions)
        return row
    }

    private fun actionButton(label: String, color: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setTextColor(color)
            textSize = 11f
            minWidth = 0
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setBackgroundColor(0x00141416)
            setOnClickListener {
                Haptics.vibrate(this@FlashPairActivity, 24)
                onClick()
            }
        }

    /** 众包档案交换的另一半：把别人贴来的行式文本直接存成一盏灯。 */
    private fun importProfile() {
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        val raw = runCatching {
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        }.getOrNull()
        val p = runCatching { FlashProfileCodec.decode(raw) }.getOrNull()
        if (p == null || p.triggerBytes() == null) {
            Toast.makeText(this, getString(R.string.flash_pair_manage_import_fail), Toast.LENGTH_SHORT).show()
            return
        }
        if (!store.save(p)) {
            status(getString(R.string.flash_pair_save_fail))
            return
        }
        ExternalFlashRegistry.syncLearned(store.list())
        refreshSavedList()
        Toast.makeText(
            this, getString(R.string.flash_pair_manage_import_ok, p.displayName),
            Toast.LENGTH_SHORT
        ).show()
    }

    // ---- 引擎回调 ----

    override fun onScanDevices(devices: List<FlashPairEngine.ScanDevice>) {
        runOnUiThread { renderDeviceGroups(devices) }
    }

    override fun onConnected(address: String) {
        runOnUiThread {
            status("${getString(R.string.flash_pair_status_connected)} · ${address.takeLast(5)}")
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            if (page == Page.CHAR || page == Page.FRAME) {
                status(getString(R.string.flash_pair_disconnect_hint))
            }
        }
    }

    override fun onWritableChars(chars: List<FlashPairEngine.CharInfo>) {
        runOnUiThread {
            if (chars.isEmpty()) {
                status(getString(R.string.flash_pair_no_write_char))
                return@runOnUiThread
            }
            charList.removeAllViews()
            var separatorAdded = false
            for (c in chars) {
                if (!c.recommended && !separatorAdded) {
                    charList.addView(sectionRow(getString(R.string.flash_pair_not_recommended)))
                    separatorAdded = true
                }
                charList.addView(charRow(c))
            }
            val dev = selectedDevice
            connectedMeta.text = buildString {
                append(dev?.name ?: "-")
                append("   ")
                append(dev?.address ?: "-")
                append("   ${chars.size} CH")
            }
            // 连上即翻页：用户不用往下滑就能看到第 2 页
            showPage(Page.CHAR)
            status("${getString(R.string.flash_pair_status_connected)} · ${chars.size} CH")
        }
    }

    override fun onWriteSent(hex: String, ok: Boolean) {
        runOnUiThread {
            lastSentHex = if (ok) hex else null
            if (ok) markTried(hex) else status(getString(R.string.flash_pair_write_fail, hex))
            updatePrimary()
        }
    }

    override fun onError(msg: String) {
        runOnUiThread {
            status(
                if (msg == "WRITE_BUSY") getString(R.string.flash_pair_write_busy) else msg
            )
        }
    }

    /** 无回调自动重连：停在试闪页，作废上一帧，避免保存「没发出去」的档案。 */
    override fun onReconnecting() {
        runOnUiThread {
            status(getString(R.string.flash_pair_reconnecting))
            lastSentHex = null
            hitFrame = null
            updatePrimary()
        }
    }

    override fun onReconnected() {
        runOnUiThread {
            status(getString(R.string.flash_pair_reconnected))
            lastSentHex = null
            hitFrame = null
            updatePrimary()
        }
    }

    override fun onWriteAck(delivered: Boolean) {
        runOnUiThread {
            if (!delivered) status(getString(R.string.flash_pair_write_rejected))
        }
    }

    // ---- 01 认灯：三组分区，灯候选置顶 ----

    private fun renderDeviceGroups(devices: List<FlashPairEngine.ScanDevice>) {
        val candidates = devices.filter { it.bonded && it.likelyFlash }
        val nearby = devices.filter { !it.bonded }
        val others = devices.filter { it.bonded && !it.likelyFlash }
        pairedList.removeAllViews()
        deviceList.removeAllViews()
        otherList.removeAllViews()
        pairedHeader.text = "${getString(R.string.flash_pair_group_paired)}  ${candidates.size}"
        for (d in candidates.take(6)) pairedList.addView(deviceRow(d))
        if (candidates.isEmpty()) pairedList.addView(emptyCandidateRow())
        nearbyHeader.text = "${getString(R.string.flash_pair_group_nearby)}  ${nearby.size}"
        for (d in nearby.take(20)) deviceList.addView(deviceRow(d))
        otherHeader.visibility = if (others.isEmpty()) View.GONE else View.VISIBLE
        for (d in others.take(8)) otherList.addView(deviceRow(d))
    }

    /** 0 候选不是死路：给出三步行动指引 + 一个就地刷新已配对的按钮。 */
    private fun emptyCandidateRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
            text = getString(R.string.flash_pair_no_candidate_hint)
            setTextColor(0xFFB8B6AE.toInt())
            textSize = 12f
            setPadding(0, dp(6), 0, dp(6))
        })
        addView(Button(context).apply {
            text = getString(R.string.flash_pair_rescan)
            setTextColor(0xFF9FE1CB.toInt())
            textSize = 12f
            minWidth = 0
            setBackgroundColor(0x00141416)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { restartScan() }
        })
    }

    private fun hintRow(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(0xFF5A5850.toInt())
        textSize = 11f
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun sectionRow(text: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(10), 0, dp(4))
        addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1
            )
            setBackgroundColor(0xFF333330.toInt())
        })
        addView(TextView(context).apply {
            this.text = text
            setTextColor(0xFF5A5850.toInt())
            textSize = 11f
            setPadding(0, dp(8), 0, 0)
        })
    }

    @SuppressLint("MissingPermission")
    private fun deviceRow(d: FlashPairEngine.ScanDevice): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setBackgroundResource(android.R.color.transparent)
            gravity = Gravity.CENTER_VERTICAL
        }
        val name = TextView(this).apply {
            text = d.name
            setTextColor(if (d.likelyFlash) 0xFF9FE1CB.toInt() else 0xFFE8E6E1.toInt())
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val meta = TextView(this).apply {
            text = deviceMeta(d)
            setTextColor(0xFF8A8880.toInt())
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        row.addView(name)
        row.addView(meta, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) })
        row.setOnClickListener {
            Haptics.vibrate(this, 24)
            selectedDevice = d
            status(getString(R.string.flash_pair_status_connecting))
            engine.connect(d.address)
        }
        return row
    }

    /** 已配对打标；无名设备给 MAC + 信号 + 服务短码，不靠名字也能认灯。 */
    private fun deviceMeta(d: FlashPairEngine.ScanDevice): String = buildString {
        if (d.bonded) append(getString(R.string.flash_pair_bonded_tag)).append("   ")
        append(d.address)
        if (d.rssi < 0) append("   ${d.rssi} dBm")
        val svc = d.serviceUuids.firstOrNull()
            ?.uppercase()
            ?.removePrefix("0000")
            ?.take(4)
        if (!svc.isNullOrEmpty()) append("   svc $svc")
    }

    private fun connectManualMac() {
        val mac = manualMac.text.toString().trim().uppercase()
        if (!MAC_REGEX.matches(mac)) {
            status(getString(R.string.flash_pair_bad_mac))
            return
        }
        selectedDevice = FlashPairEngine.ScanDevice(
            name = "MAC $mac", address = mac, rssi = 0
        )
        status(getString(R.string.flash_pair_status_connecting))
        engine.connect(mac)
    }

    // ---- 02 通道 ----

    private fun charRow(c: FlashPairEngine.CharInfo): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        val uuid = TextView(this).apply {
            text = buildString {
                append(c.charUuid.uppercase().removePrefix("0000").take(8))
                append("   ")
                append(
                    getString(
                        if (c.recommended) R.string.flash_pair_recommended
                        else R.string.flash_pair_config_char
                    )
                )
            }
            setTextColor(if (c.recommended) 0xFF9FE1CB.toInt() else 0xFF8A8880.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val meta = TextView(this).apply {
            text = "svc ${c.serviceUuid.uppercase().removePrefix("0000").take(4)}   ${c.propsText}"
            setTextColor(0xFF8A8880.toInt())
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        row.addView(uuid)
        row.addView(meta, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) })
        row.setOnClickListener {
            Haptics.vibrate(this, 24)
            android.util.Log.w("FlashPairNav", "channel tapped ${c.charUuid} wnr=${c.writeNoResponse}")
            selectedChar = c
            enterFrameStep()
        }
        return row
    }

    // ---- 03 试闪：未试 / 已试分组 + 命中确认 ----

    private fun enterFrameStep() {
        android.util.Log.w("FlashPairNav", "enterFrameStep ${selectedChar?.charUuid}")
        clearFrameState()
        for (hex in FlashPairEngine.CANDIDATE_FRAMES) {
            frameRows[hex] = frameRow(hex)
        }
        updateFrameGroups()
        manualHex.setText("")
        lastSentHex = null
        selectedChar?.let {
            trialChannel.text = getString(
                R.string.flash_pair_trial_channel,
                it.charUuid.uppercase().removePrefix("0000").take(8)
            )
        }
        showPage(Page.FRAME)
    }

    /** 行：点左边试发，点右边「闪了」确认命中；命中的那条才会被保存。 */
    private fun frameRow(hex: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            gravity = Gravity.CENTER_VERTICAL
        }
        val hexText = TextView(this).apply {
            text = hex
            setTextColor(0xFFE8E6E1.toInt())
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val hit = TextView(this).apply {
            tag = "hit"
            text = getString(R.string.flash_pair_hit)
            setTextColor(0xFF8A8880.toInt())
            textSize = 11f
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        row.addView(hexText)
        row.addView(View(this), LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        row.addView(hit)
        row.setOnClickListener {
            Haptics.vibrate(this, 24)
            selectedChar?.let { engine.writeFrame(it, hex) }
        }
        hit.setOnClickListener {
            Haptics.vibrate(this, 24)
            if (!triedFrames.contains(hex)) {
                status(getString(R.string.flash_pair_hit_first))
                return@setOnClickListener
            }
            hitFrame = hex
            lastSentHex = hex
            refreshHitMarks()
            updatePrimary()
            status("HIT $hex")
        }
        return row
    }

    private fun markTried(hex: String) {
        if (!frameRows.containsKey(hex)) frameRows[hex] = frameRow(hex)
        triedFrames.add(hex)
        updateFrameGroups()
    }

    private fun updateFrameGroups() {
        frameList.removeAllViews()
        triedList.removeAllViews()
        for ((hex, row) in frameRows) {
            if (triedFrames.contains(hex)) triedList.addView(row) else frameList.addView(row)
        }
        untestedHeader.text = getString(
            R.string.flash_pair_frame_untested, frameRows.size - triedFrames.size
        )
        triedHeader.text = getString(
            R.string.flash_pair_frame_tried, triedFrames.size,
            getString(if (triedExpanded) R.string.flash_pair_collapse else R.string.flash_pair_expand)
        )
        triedList.visibility = if (triedExpanded) View.VISIBLE else View.GONE
        refreshHitMarks()
    }

    private fun refreshHitMarks() {
        for ((hex, row) in frameRows) {
            val hit = row.findViewWithTag<TextView>("hit") ?: continue
            if (hex == hitFrame) {
                hit.text = getString(R.string.flash_pair_hit_mark)
                hit.setTextColor(0xFF9FE1CB.toInt())
            } else {
                hit.text = getString(R.string.flash_pair_hit)
                hit.setTextColor(0xFF8A8880.toInt())
            }
        }
    }

    private fun sendManual() {
        val hex = manualHex.text.toString().trim()
        if (hex.isEmpty()) return
        if (FlashTriggerCommand.parseHex(hex) == null) {
            status(getString(R.string.flash_pair_bad_hex))
            return
        }
        selectedChar?.let { engine.writeFrame(it, hex) }
    }

    private fun backToCharStep() {
        selectedChar = null
        lastSentHex = null
        hitFrame = null
        showPage(Page.CHAR)
        status(getString(R.string.flash_pair_try_next))
    }

    // ---- 04 完成 ----

    private fun saveProfile() {
        val dev = selectedDevice ?: return
        val char = selectedChar ?: return
        val hex = hitFrame ?: return
        val profile = FlashProfile(
            brandId = "learned",
            displayName = dev.name,
            deviceName = dev.name,
            address = dev.address.uppercase(),
            serviceUuid = char.serviceUuid,
            writeCharUuid = char.charUuid,
            writeNoResponse = char.writeNoResponse,
            triggerHex = hex,
            // 同步偏移按灯型定：氙气灯（A2）出光快 ≈ -80ms，COB 持续灯起辉慢 ≈ -200ms。
            // 用户可在档案文本里手改 offset 覆盖（导入时按行读取）。
            syncOffsetMs = if (dev.name.contains("A2", ignoreCase = true)) -80 else -200,
            pairedAt = System.currentTimeMillis()
        )
        if (!store.save(profile)) {
            status(getString(R.string.flash_pair_save_fail))
            return
        }
        // vc86：配对成功回传 OK —— MainActivity 据此知道「这次 EXT 档有灯可用了」
        setResult(android.app.Activity.RESULT_OK)
        savedProfile = profile
        ExternalFlashRegistry.syncLearned(store.list())
        refreshSavedList()
        doneText.text = getString(
            R.string.flash_pair_saved,
            profile.displayName, profile.triggerHex
        )
        doneMeta.text = getString(
            R.string.flash_pair_done_meta,
            profile.address,
            profile.writeCharUuid.uppercase().removePrefix("0000").take(8),
            profile.triggerHex,
            SimpleDateFormat("yyyy.MM.dd", Locale.US).format(Date(profile.pairedAt))
        )
        showPage(Page.DONE)
        status(getString(R.string.flash_pair_status_paired))
    }

    /** 档案是可读的行式文本，一键复制即可贴给朋友 / 论坛（众包档案库交换口）。 */
    private fun copyProfile() {
        val p = savedProfile
        if (p == null) {
            Toast.makeText(this, getString(R.string.flash_pair_copy_empty), Toast.LENGTH_SHORT).show()
            return
        }
        copyProfile(p)
    }

    /** 完成页复制刚保存的档案；管理页复制任意在档灯（行式文本，可直接贴给别人）。 */
    private fun copyProfile(p: FlashProfile) {
        val clip = android.content.ClipData.newPlainText(
            "FilmCam flash profile", FlashProfileCodec.encode(p)
        )
        (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.flash_pair_copied), Toast.LENGTH_SHORT).show()
    }

    private fun testSavedTrigger() {
        val p = savedProfile ?: return
        val char = selectedChar ?: return
        engine.writeFrame(char, p.triggerHex)
    }

    private fun refreshSavedList() {
        val list = store.list()
        savedList.removeAllViews()
        savedEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        for (p in list) {
            savedList.addView(savedRow(p))
        }
        renderManageList(list)
    }

    private fun savedRow(p: FlashProfile): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            gravity = Gravity.CENTER_VERTICAL
        }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val name = TextView(this).apply {
            text = p.displayName
            setTextColor(0xFFE8E6E1.toInt())
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val meta = TextView(this).apply {
            text = "${p.address}   trigger ${p.triggerHex}"
            setTextColor(0xFF8A8880.toInt())
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        info.addView(name)
        info.addView(meta, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) })
        row.addView(info, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        val del = Button(this).apply {
            text = getString(R.string.flash_pair_delete)
            setTextColor(0xFFF0997B.toInt())
            textSize = 11f
            minWidth = 0
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setBackgroundColor(0x00141416)
        }
        del.setOnClickListener {
            Haptics.vibrate(this, 24)
            store.delete(p.address)
            ExternalFlashRegistry.syncLearned(store.list())
            refreshSavedList()
        }
        row.addView(del)
        return row
    }

    private fun status(s: String) {
        pairStatus.text = s
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** 从设置面板「外闪灯管理」进入时置 true：第一屏给档案，不先空扫。 */
        const val EXTRA_MANAGE = "com.filmcam.app.flash.EXTRA_MANAGE"
        private val MAC_REGEX = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
    }
}
