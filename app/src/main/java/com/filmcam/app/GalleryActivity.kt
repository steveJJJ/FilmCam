package com.filmcam.app

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.spektralab.engine.FilmStockMapper
import com.filmcam.app.develop.DevelopController
import com.filmcam.app.film.RollInfo
import com.filmcam.app.film.UndevelopedFilmStore
import coil.load

class GalleryActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "GalleryActivity"
    }

    private lateinit var rvGallery: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var layoutUndeveloped: LinearLayout
    private lateinit var rvUndeveloped: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        rvGallery = findViewById(R.id.rvGallery)
        tvEmpty = findViewById(R.id.tvEmpty)
        layoutUndeveloped = findViewById(R.id.layoutUndeveloped)
        rvUndeveloped = findViewById(R.id.rvUndeveloped)

        // 列数按屏宽自适应：每格最小约 104dp，手机 3 列、大屏/折叠屏展开可到 6 列。
        // 原来固定 3 列 + 400px 行高，在 2K 屏上是细扁条，在折叠屏上又过大。
        val dm = resources.displayMetrics
        val minItemPx = (104f * dm.density).toInt().coerceAtLeast(1)
        val spanCount = (dm.widthPixels / minItemPx).coerceIn(3, 6)
        rvGallery.layoutManager = GridLayoutManager(this, spanCount)
        rvUndeveloped.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        // 机身配色（黑钛 / 钛银）：相册不是机身，但跟着整机配色走，
        // 免得从银色机身一点进相册又变回全黑，割裂。
        com.filmcam.app.ui.BodyTheme.load(this)
        val root = findViewById<View>(android.R.id.content)
        com.filmcam.app.ui.BodyTheme.applyToViewTree(root)
        root.background = com.filmcam.app.ui.BodyTheme.bodyBackground()
        com.filmcam.app.ui.BodyTheme.applySystemBars(window)
    }

    override fun onResume() {
        super.onResume()
        loadUndevelopedRolls()
        loadPhotos()
    }

    // ---------- 未冲洗胶卷区 ----------

    private fun loadUndevelopedRolls() {
        val rolls = UndevelopedFilmStore.undevelopedRolls()
        if (rolls.isEmpty()) {
            layoutUndeveloped.visibility = View.GONE
            return
        }
        layoutUndeveloped.visibility = View.VISIBLE
        rvUndeveloped.adapter = UndevelopedAdapter(rolls)
    }

    inner class UndevelopedAdapter(private val items: List<RollInfo>) :
        RecyclerView.Adapter<UndevelopedAdapter.VH>() {

        inner class VH(val card: View) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            // 简单在代码里拼一个横向卡片，避免新增布局文件
            val ctx = parent.context
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 18, 24, 18)
                setBackgroundColor(0xFF2A0808.toInt())
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = 12 }
            }
            val nameTv = TextView(ctx).apply {
                setTextColor(0xFFFFD7C4.toInt())
                textSize = 14f
                tag = "name"
            }
            val countTv = TextView(ctx).apply {
                setTextColor(0xFFFF9A86.toInt())
                textSize = 12f
                tag = "count"
            }
            val btn = Button(ctx).apply {
                text = "冲洗"
                setTextColor(0xFFFFFFFF.toInt())
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF8B1A1A.toInt())
                tag = "btn"
            }
            card.addView(nameTv)
            card.addView(countTv)
            card.addView(btn)
            return VH(card)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val roll = items[position]
            val ctx = holder.card.context
            (holder.card.findViewWithTag<TextView>("name")).text =
                FilmStockMapper.stockDisplayName(roll.filmStockId)
            (holder.card.findViewWithTag<TextView>("count")).text = "${roll.shotCount}/36"
            (holder.card.findViewWithTag<Button>("btn")).setOnClickListener {
                DevelopController.startDevelopment(ctx, roll.rollId)
            }
        }

        override fun getItemCount() = items.size
    }

    // ---------- 已冲洗相册网格 ----------

    private fun loadPhotos() {
        val photos = mutableListOf<Uri>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)

        // API 29+：RELATIVE_PATH 精确定位 DCIM/FilmCam/，并显式排除 IS_PENDING=1 的未冲洗帧。
        //   pending 项对本应用自身是可见的，若不排除，用户会在冲洗前就在相册里看到原片，
        //   破坏"拍完不可见、冲洗才出片"的核心体验。
        // API 26-28：没有 RELATIVE_PATH / IS_PENDING 这两列，查它会抛
        //   SQLiteException(no such column)，改用 DATA 路径前缀匹配。
        val selection: String
        val selectionArgs: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND " +
                "${MediaStore.Images.Media.IS_PENDING} = 0"
            selectionArgs = arrayOf("DCIM/FilmCam/")
        } else {
            selection = "${MediaStore.Images.Media.DATA} LIKE ?"
            selectionArgs = arrayOf("%/DCIM/FilmCam/%")
        }
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)
                ?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        photos.add(Uri.withAppendedPath(collection, id.toString()))
                    }
                }
        } catch (e: Exception) {
            // 查询失败不能连累整个页面：降级为空态，并提示用户
            Log.e(TAG, "查询相册失败", e)
            Toast.makeText(this, "读取相册失败", Toast.LENGTH_SHORT).show()
        }

        if (photos.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rvGallery.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            rvGallery.visibility = View.VISIBLE
            rvGallery.adapter = GalleryAdapter(photos)
        }
    }

    inner class GalleryAdapter(private val items: List<Uri>) :
        RecyclerView.Adapter<GalleryAdapter.ViewHolder>() {

        inner class ViewHolder(val iv: ImageView) : RecyclerView.ViewHolder(iv)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            // 正方形容器：宽度由 GridLayoutManager 均分，高度自动等于宽度。
            // 原先写死 400px，在 2K 屏上缩成细扁条、在折叠屏上过大。
            val gap = (2 * parent.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val iv = com.filmcam.app.ui.SquareImageView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(gap, gap, gap, gap)
            }
            val holder = ViewHolder(iv)
            // 点击事件只设置一次，位置取 holder 的 bindingAdapterPosition 避免复用错乱
            iv.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val intent = android.content.Intent(this@GalleryActivity, PhotoViewerActivity::class.java).apply {
                        putStringArrayListExtra(PhotoViewerActivity.EXTRA_URIS, ArrayList(items.map { it.toString() }))
                        putExtra(PhotoViewerActivity.EXTRA_POSITION, pos)
                    }
                    startActivity(intent)
                }
            }
            return holder
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            // Coil：自动内存/磁盘缓存 + 按 View 尺寸采样解码，滚动时自动取消复用项的旧请求
            holder.iv.load(items[position]) {
                crossfade(true)
            }
        }

        override fun getItemCount() = items.size
    }
}
