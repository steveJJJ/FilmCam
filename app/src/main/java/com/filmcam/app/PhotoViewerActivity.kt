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

        uris = intent.getStringArrayListExtra(EXTRA_URIS) ?: arrayListOf()
        val position = intent.getIntExtra(EXTRA_POSITION, 0)

        viewPager = findViewById(R.id.viewPager)
        panelInfo = findViewById(R.id.panelInfo)

        viewPager.adapter = PhotoPagerAdapter(this, uris)
        viewPager.setCurrentItem(position, false)

        // 拍摄同款：把这张的胶卷 + 冲洗参数带回取景器
        findViewById<View>(R.id.btnShootSame).setOnClickListener { shootSame() }

        // 切换照片时同步刷新面板内容
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
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
                this, "这张照片没有可复现的拍摄参数", android.widget.Toast.LENGTH_SHORT
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
            info.params.printExposure?.let { putExtra(MainActivity.EXTRA_SAME_EXPOSURE, it) }
        }
        startActivity(intent)
        finish()
    }

    /** 由 Fragment 在图片被点击时回调，切换面板可见性。 */
    fun onPhotoTapped() {
        if (!::panelInfo.isInitialized) return
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
        tvRollId.text = if (info.rollId.isBlank()) "" else "胶卷编号 ${info.rollId}"

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
        tvShooting.text = shootParts.joinToString(" · ")

        // 拍摄时间
        tvShotTime.text = if (info.dateTimeOriginal.isNotBlank()) {
            "拍摄于 ${info.dateTimeOriginal}"
        } else {
            ""
        }

        // 冲洗参数：相纸 + 曝光/颗粒/辉光/眩光
        val printName = if (info.printStockId.isNotBlank()) {
            info.printStockId
        } else {
            "默认"
        }
        tvPrintStock.text = "相纸 $printName"

        val p = info.params
        val devParts = mutableListOf<String>()
        p.printExposure?.let { devParts += "曝光 %.2f".format(it) }
        p.grainAmount?.let { devParts += "颗粒 %.2f".format(it) }
        p.halationAmount?.let { devParts += "辉光 %.2f".format(it) }
        p.glareAmount?.let { devParts += "眩光 %.2f".format(it) }
        tvDevParams.text = if (devParts.isEmpty()) {
            "默认冲洗参数"
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
