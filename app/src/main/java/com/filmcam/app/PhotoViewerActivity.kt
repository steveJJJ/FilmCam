package com.filmcam.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.filmcam.app.develop.ExifReader
import com.filmcam.app.film.CaptureExifFormat
import com.filmcam.app.ui.Haptics
import com.filmcam.app.ui.ZoomableImageView
import com.filmcam.app.ui.animatePanelIn
import com.filmcam.app.ui.animatePanelOut
import com.filmcam.app.ui.hapticClick

/**
 * 成片查看器：ViewPager2 左右滑 + 点击展开胶卷信息面板。
 *
 * 面板内容来自成片 EXIF（写入方见 ContaxExif）：
 *  胶卷名 / 胶卷编号 / 拍摄参数 / 冲洗参数 / 引擎信息。
 */
class PhotoViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URIS = "extra_uris"
        const val EXTRA_POSITION = "extra_position"
    }

    private lateinit var uris: List<String>
    private lateinit var panelInfo: View
    private lateinit var viewPager: ViewPager2

    private var panelVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_viewer)

        // 边到边：照片铺满全屏，仅底部信息面板让出导航栏
        com.filmcam.app.ui.EdgeToEdge.setup(this, null, findViewById(R.id.panelInfo))

        uris = intent.getStringArrayListExtra(EXTRA_URIS) ?: arrayListOf()
        val position = intent.getIntExtra(EXTRA_POSITION, 0)

        viewPager = findViewById(R.id.viewPager)
        // ViewPager2 只要可交互，就会在 DOWN 后 CANCEL 子 View 的触摸，
        // 翻页权统一交给 ZoomableImageView：1x 时它用 onSwipePage 回调驱动 setCurrentItem。
        viewPager.isUserInputEnabled = false
        panelInfo = findViewById(R.id.panelInfo)

        viewPager.adapter = PhotoPagerAdapter(this, uris)
        viewPager.setCurrentItem(position, false)

        // 拍摄同款：把这张的胶卷 + 冲洗参数带回取景器。
        // 这是「带着这卷胶卷回机身」的确认动作，触感给得比普通点击重一档
        findViewById<View>(R.id.btnShootSame).hapticClick(ms = 30, amp = 210) { shootSame() }

        // 系统分享：按钮已移入底部信息面板标题行，与 EXIF 一起显隐 ——
        // 看照片时整块收起，屏幕上没有常驻的红色标志；点照片展开面板后才出现。
        // 面板 gone 时该子 view 不可点，无需再单独判显隐，也不需要状态栏 inset 补偿。
        // 与拍摄同款同一档确认触感。
        val btnShare = findViewById<View>(R.id.btnShare)
        btnShare.hapticClick(ms = 30, amp = 210) { shareCurrent() }

        // 切换照片时同步刷新面板内容；顺手给一记轻 tick ——
        // 左右拨片是看片的核心动作，纯视觉滑动会觉得「这页没反应」。
        // setCurrentItem 在挂回调之前，冷启动不会误震
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                // 页到位的轻 tick（即时确认已在 onSwipePage 给出，这里只做微弱收尾，
                // 避免快速连翻时两记震动叠成糊震）。契约要求本处保留本前缀的调用。
                Haptics.vibrate(this@PhotoViewerActivity, 8, 90)
                if (panelVisible) renderPanel(position)
            }
        })
    }

    /**
     * 拍摄同款：读取当前成片的 EXIF（胶卷 stockId + 冲洗参数快照），
     * 通过 Intent extras 交回 MainActivity 套用，然后回到取景器。
     * 没有 EXIF 或没有胶卷信息的老照片给出提示并留在原处。
     */
    private fun shootSame() {
        val info = ExifReader.read(this, Uri.parse(uris[viewPager.currentItem]))
        if (info == null || info.filmStockId.isBlank()) {
            android.widget.Toast.makeText(
                this, getString(R.string.pf_same_no_params), android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        val intent = android.content.Intent(this, MainActivity::class.java).apply {
            // 复用栈底的取景器实例（onNewIntent 套用参数），而不是新开一个相机
            addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_SAME_FILM, info.filmStockId)
            if (info.printStockId.isNotBlank()) {
                putExtra(MainActivity.EXTRA_SAME_PAPER, info.printStockId)
            }
            info.params.grainAmount?.let { putExtra(MainActivity.EXTRA_SAME_GRAIN, it) }
            info.params.halationAmount?.let { putExtra(MainActivity.EXTRA_SAME_HALATION, it) }
            info.params.glareAmount?.let { putExtra(MainActivity.EXTRA_SAME_GLARE, it) }
            info.params.exposureEv?.let { putExtra(MainActivity.EXTRA_SAME_EXPOSURE, it) }
            // 输出风格也一起带回去：同一卷用 Frontier 扫的和用放大机印的，本来就是两张不同的片子
            info.params.outputStyle?.let { putExtra(MainActivity.EXTRA_SAME_STYLE, it.prefs) }
            // 拍摄参数：闪光/白平衡/曝光补偿。慢门与失焦刻意不带 —— 那是这一张的意外，
            // 不是配方；强行复刻只会让「拍摄同款」变成必糊键。
            info.shot?.let { shot ->
                putExtra(MainActivity.EXTRA_SAME_FLASH, shot.flash)
                putExtra(MainActivity.EXTRA_SAME_WB, shot.awbMode)
                putExtra(MainActivity.EXTRA_SAME_EV, shot.ev)
                putExtra(MainActivity.EXTRA_SAME_FLASH_EV, shot.flashCompEv)
            }
        }
        startActivity(intent)
        finish()
    }

    /**
     * 系统分享当前这张成片。
     *
     * uris 由 GalleryActivity.loadPhotos() 传入 —— 那一侧的查询只取 DCIM/FilmCam/
     * 且 IS_PENDING=0 的成片，母片与未冲洗帧根本不在这个列表里。这里再按 id 复核
     * 一次 final 状态，避免任何上游改动把未冲洗帧漏进分享通道。
     */
    private fun shareCurrent() {
        val pos = viewPager.currentItem
        if (pos !in uris.indices) return
        val uri = Uri.parse(uris[pos])
        if (!isFinalPrint(uri)) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.viewer_share)))
    }

    /** 只认 DCIM/FilmCam 下 IS_PENDING=0 的成片；API 26-28 没有 IS_PENDING 列，信任相册列表的来源过滤。 */
    private fun isFinalPrint(uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val id = uri.lastPathSegment?.toLongOrNull() ?: return false
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media._ID}=? AND " +
            "${MediaStore.Images.Media.RELATIVE_PATH}=? AND " +
            "${MediaStore.Images.Media.IS_PENDING}=0"
        return try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arrayOf(id.toString(), "DCIM/FilmCam/"),
                null
            )?.use { it.moveToFirst() } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 缩放状态回传（来自 ZoomableImageView）。
     * 1x（scale<=1.01f）和放大态都不把触摸权交还 ViewPager2 —— 一旦交还，
     * 它会在 DOWN 后立刻 CANCEL 子 View，手势全废。翻页统一走 [onSwipePage]。
     */
    fun onZoomChanged(scale: Float) {
        if (!::viewPager.isInitialized) return
        viewPager.isUserInputEnabled = false
    }

    /** 1x 横向滑动翻页：direction=-1 上一张 / +1 下一张。 */
    fun onSwipePage(direction: Int) {
        if (!::viewPager.isInitialized || !::uris.isInitialized) return
        val next = viewPager.currentItem + direction
        if (next !in uris.indices) {
            Haptics.vibrate(this@PhotoViewerActivity, 10, 90)  // 到头轻震，和第 1/12 张护栏同档
            return
        }
        // 手指判向的那一帧就给确认震 —— onPageSelected 要等 smooth scroll 走完
        // （200-300ms 之后才回调），且原来的 12ms/110 是全工程最弱一档，
        // 两者叠加就是你感觉「滑动没有震动」的原因。18ms/150 与设置面板翻档同档。
        Haptics.vibrate(this@PhotoViewerActivity, 18, 150)
        viewPager.setCurrentItem(next, true)
    }

    /** 由 Fragment 在图片被点击时回调，切换面板可见性。 */
    fun onPhotoTapped() {
        if (!::panelInfo.isInitialized) return
        Haptics.vibrate(this, 18, 150)   // 翻面板：与机身按钮同级的确认感
        panelVisible = !panelVisible
        // vc103：改为复用全 App 统一的面板动效（Motion.animatePanelIn/Out）——
        // 淡入 + 12dp 上浮入场、8dp 下沉淡出退场，与设置面板/关于页完全一致。
        // 原实现只有纯 alpha，黑底上几乎看不出变化（用户反馈「没有动效」）；
        // reduced-motion 下统一函数会自动去掉位移、只留 alpha。
        if (panelVisible) {
            renderPanel(viewPager.currentItem)
            panelInfo.visibility = View.VISIBLE
            panelInfo.animatePanelIn()
        } else {
            panelInfo.animatePanelOut { }
        }
    }

    /** 读取当前照片的 EXIF 并填充面板。 */
    private fun renderPanel(position: Int) {
        if (position !in uris.indices) return
        val uri = Uri.parse(uris[position])
        val info = ExifReader.read(this, uri)

        val tvFilmName = panelInfo.findViewById<TextView>(R.id.tvFilmName)
        val tvRollId = panelInfo.findViewById<TextView>(R.id.tvRollId)
        val tvShooting = panelInfo.findViewById<TextView>(R.id.tvShooting)
        val tvCapture = panelInfo.findViewById<TextView>(R.id.tvCapture)
        val tvCaptureDetail = panelInfo.findViewById<TextView>(R.id.tvCaptureDetail)
        val tvShotTime = panelInfo.findViewById<TextView>(R.id.tvShotTime)
        val tvPrintStock = panelInfo.findViewById<TextView>(R.id.tvPrintStock)
        val tvDevParams = panelInfo.findViewById<TextView>(R.id.tvDevParams)
        val tvEngine = panelInfo.findViewById<TextView>(R.id.tvEngine)
        val tvDims = panelInfo.findViewById<TextView>(R.id.tvDims)

        if (info == null) {
            tvFilmName.text = getString(R.string.info_no_exif)
            tvRollId.text = ""
            tvShooting.text = ""
            tvCapture.text = ""
            tvCaptureDetail.text = ""
            tvShotTime.text = ""
            tvPrintStock.text = ""
            tvDevParams.text = ""
            tvEngine.text = ""
            tvDims.text = ""
            return
        }

        // 如果所有胶卷字段都空（旧照片或 EXIF 未写入），显示提示
        val hasFilmInfo = info.displayName.isNotBlank() ||
            info.filmStockId.isNotBlank() ||
            info.rollId.isNotBlank()
        if (!hasFilmInfo && info.make.isBlank() && info.model.isBlank()) {
            tvFilmName.text = getString(R.string.info_no_exif)
            tvRollId.text = ""
            tvShooting.text = ""
            tvCapture.text = ""
            tvCaptureDetail.text = ""
            tvShotTime.text = ""
            tvPrintStock.text = ""
            tvDevParams.text = ""
            tvEngine.text = ""
            tvDims.text = ""
            return
        }

        // 胶卷名：优先 EXIF 里的显示名，回落 stock id
        val filmName = info.displayName.ifBlank { info.filmStockId }
        tvFilmName.text = filmName
        tvRollId.text = if (info.rollId.isBlank()) "" else getString(R.string.pf_roll_id, info.rollId)

        // 拍摄参数：CONTAX T3 · 35mm f/2.8 · ISO 400
        val shootParts = mutableListOf<String>()
        if (info.make.isNotBlank() || info.model.isNotBlank()) {
            shootParts += listOf(info.make, info.model).filter { it.isNotBlank() }.joinToString(" ")
        }
        if (info.focalLength.isNotBlank()) {
            shootParts += "${info.focalLength}mm"
        }
        if (info.fNumber.isNotBlank()) {
            shootParts += "f/${info.fNumber}"
        }
        if (info.iso.isNotBlank()) {
            shootParts += "ISO ${info.iso}"
        }
        // 这一张的「光学设置」：闪光、慢门快门、失焦 —— 拍摄同款能不能复现就看这几项
        info.shot?.let { shot ->
            if (shot.flash != "OFF") shootParts += getString(R.string.pf_flash_short)
            if (shot.shutterSec > 0f) {
                shootParts += if (shot.shutterSec >= 1f) {
                    String.format(java.util.Locale.US, "%.1fs", shot.shutterSec)
                } else {
                    "1/" + kotlin.math.round(1f / shot.shutterSec).toInt()
                }
            }
            if (shot.defocus) shootParts += getString(R.string.pf_defocus_short)
        }
        tvShooting.text = shootParts.joinToString(" · ")

        // 实拍参数：模拟机身铭牌之外的传感器真值，直接读标准 EXIF 字段。
        // vc103：面板只保留一个 ISO（上方 tvShooting 的胶卷模拟感光度），
        // 这里不再重复写「实拍 ISO」；快门 / 外闪灯名 / 下方诊断行照旧保留，
        // 外闪过曝复盘能力不丢。EXIF 落盘的 sensorIso 字段不受影响。
        val captureParts = mutableListOf<String>()
        if (info.exposureTime.isNotBlank()) {
            captureParts += getString(R.string.pf_capture_shutter, CaptureExifFormat.shutterLabel(info.exposureTime))
        }
        info.shot?.let { shot ->
            when {
                shot.flash.equals("EXTERNAL", ignoreCase = true) -> captureParts += getString(
                    R.string.pf_capture_ext_flash, shot.flashDevice.ifBlank { "EXT" }
                )
                shot.flash.equals("BUILT_IN", ignoreCase = true) ->
                    captureParts += getString(R.string.pf_capture_builtin)
            }
        }
        tvCapture.text = captureParts.joinToString(" · ")

        // 外闪身份与指令（档案快照）：MAC / 通道 / 触发帧 / 细档补偿
        val detailParts = mutableListOf<String>()
        info.shot?.let { shot ->
            if (shot.flashAddress.isNotBlank()) detailParts += shot.flashAddress
            if (shot.flashChannel.isNotBlank()) {
                detailParts += getString(R.string.pf_capture_channel, CaptureExifFormat.shortUuid(shot.flashChannel))
            }
            if (shot.flashTriggerHex.isNotBlank()) {
                detailParts += getString(R.string.pf_capture_trigger, shot.flashTriggerHex)
            }
            if (shot.flash.equals("EXTERNAL", ignoreCase = true) && shot.flashCompEv != 0f) {
                detailParts += getString(R.string.pf_capture_flash_ev, shot.flashCompEv)
            }
        }
        tvCaptureDetail.text = detailParts.joinToString(" · ")

        // 拍摄时间
        tvShotTime.text = if (info.dateTimeOriginal.isNotBlank()) {
            getString(R.string.pf_shot_at, info.dateTimeOriginal)
        } else {
            ""
        }

        // 冲洗参数：相纸 + 曝光/颗粒/辉光/眩光
        val printName = if (info.printStockId.isNotBlank()) {
            info.printStockId
        } else {
            getString(R.string.pf_paper_default)
        }
        tvPrintStock.text = getString(R.string.pf_paper, printName)

        val p = info.params
        val devParts = mutableListOf<String>()
        p.exposureEv?.let { devParts += getString(R.string.pf_exposure, it) }
        p.grainAmount?.let { devParts += getString(R.string.pf_grain, it) }
        p.halationAmount?.let { devParts += getString(R.string.pf_halation, it) }
        p.glareAmount?.let { devParts += getString(R.string.pf_glare, it) }
        tvDevParams.text = if (devParts.isEmpty()) {
            getString(R.string.pf_dev_default)
        } else {
            devParts.joinToString(" · ")
        }

        // 引擎信息
        tvEngine.text = buildString {
            append(if (info.software.isNotBlank()) info.software else "SpektraLab")
            if (info.filmStockId.isNotBlank()) append(" · ${info.filmStockId}")
        }
        tvDims.text = info.dimensions
    }

    inner class PhotoPagerAdapter(
        fa: FragmentActivity,
        private val items: List<String>
    ) : FragmentStateAdapter(fa) {

        override fun getItemCount() = items.size

        override fun createFragment(position: Int): Fragment {
            return PhotoFragment.newInstance(items[position])
        }
    }

    class PhotoFragment : Fragment() {
        private var uri: String? = null
        private var zoomView: ZoomableImageView? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            uri = arguments?.getString("uri")
        }

        override fun onCreateView(
            inflater: android.view.LayoutInflater,
            container: android.view.ViewGroup?,
            savedInstanceState: Bundle?
        ): android.view.View {
            val zv = ZoomableImageView(requireContext()).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                // 放大时 View 内部不回调 onTap（单指平移/捏合不误触面板）；1x 单击照旧切面板
                onTap = { (activity as? PhotoViewerActivity)?.onPhotoTapped() }
                onScaleChanged = { s -> (activity as? PhotoViewerActivity)?.onZoomChanged(s) }
                // 1x 横向滑动 → 宿主 setCurrentItem（ViewPager2 自身不再拦截触摸）
                onSwipePage = { dir -> (activity as? PhotoViewerActivity)?.onSwipePage(dir) }
            }
            zoomView = zv
            uri?.let { zv.setImageUri(Uri.parse(it), this@PhotoFragment) }
            return zv
        }

        override fun onDestroyView() {
            // 释放 RegionDecoder / fd / bitmap，并把翻页权交还宿主
            zoomView?.release()
            zoomView = null
            super.onDestroyView()
        }

        companion object {
            fun newInstance(uri: String): PhotoFragment {
                return PhotoFragment().apply {
                    arguments = android.os.Bundle().apply { putString("uri", uri) }
                }
            }
        }
    }
}
