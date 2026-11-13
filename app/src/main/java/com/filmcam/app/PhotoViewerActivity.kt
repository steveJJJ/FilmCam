package com.filmcam.app

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.filmcam.app.develop.ExifReader
import com.filmcam.app.ui.Haptics
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
        panelInfo = findViewById(R.id.panelInfo)

        viewPager.adapter = PhotoPagerAdapter(this, uris)
        viewPager.setCurrentItem(position, false)

        // 拍摄同款：把这张的胶卷 + 冲洗参数带回取景器。
        // 这是「带着这卷胶卷回机身」的确认动作，触感给得比普通点击重一档
        findViewById<View>(R.id.btnShootSame).hapticClick(ms = 30, amp = 210) { shootSame() }

        // 切换照片时同步刷新面板内容；顺手给一记轻 tick ——
        // 左右拨片是看片的核心动作，纯视觉滑动会觉得「这页没反应」。
        // setCurrentItem 在挂回调之前，冷启动不会误震
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                Haptics.vibrate(this@PhotoViewerActivity, 12, 110)
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
            }
        }
        startActivity(intent)
        finish()
    }

    /** 由 Fragment 在图片被点击时回调，切换面板可见性。 */
    fun onPhotoTapped() {
        if (!::panelInfo.isInitialized) return
        Haptics.vibrate(this, 18, 150)   // 翻面板：与机身按钮同级的确认感
        panelVisible = !panelVisible
        if (panelVisible) {
            renderPanel(viewPager.currentItem)
            panelInfo.visibility = View.VISIBLE
            panelInfo.alpha = 0f
            panelInfo.animate().alpha(1f).setDuration(180).start()
        } else {
            panelInfo.animate().alpha(0f).setDuration(150)
                .withEndAction { panelInfo.visibility = View.GONE }
                .start()
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
        val tvShotTime = panelInfo.findViewById<TextView>(R.id.tvShotTime)
        val tvPrintStock = panelInfo.findViewById<TextView>(R.id.tvPrintStock)
        val tvDevParams = panelInfo.findViewById<TextView>(R.id.tvDevParams)
        val tvEngine = panelInfo.findViewById<TextView>(R.id.tvEngine)
        val tvDims = panelInfo.findViewById<TextView>(R.id.tvDims)

        if (info == null) {
            tvFilmName.text = getString(R.string.info_no_exif)
            tvRollId.text = ""
            tvShooting.text = ""
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

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            uri = arguments?.getString("uri")
        }

        override fun onCreateView(
            inflater: android.view.LayoutInflater,
            container: android.view.ViewGroup?,
            savedInstanceState: Bundle?
        ): android.view.View {
            val iv = ImageView(requireContext()).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            uri?.let {
                try {
                    val input = requireContext().contentResolver.openInputStream(Uri.parse(it))
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(input, null, bounds)
                    input?.close()
                    val sampleSize = (bounds.outWidth / 1080).coerceAtLeast(1)
                    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    val bmp = requireContext().contentResolver.openInputStream(Uri.parse(it))?.use { stream ->
                        BitmapFactory.decodeStream(stream, null, opts)
                    }
                    iv.setImageBitmap(bmp)
                } catch (e: Exception) { }
            }
            // 点击照片 → 切换信息面板。
            // ViewPager2 内部 RecyclerView 会拦截触摸，但单击（无水平滑动）不会被拦截。
            // setOnClickListener 在 ViewPager2 中可以正常收到单击事件。
            iv.setOnClickListener {
                (activity as? PhotoViewerActivity)?.onPhotoTapped()
            }
            return iv
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
