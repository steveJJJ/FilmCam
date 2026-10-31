package com.filmcam.app

import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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

        rvGallery.layoutManager = GridLayoutManager(this, 3)
        rvUndeveloped.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
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
        // 精确匹配 DCIM/FilmCam/ 避免误查 DCIM/.FilmCamUndeveloped/ 残留
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf("DCIM/FilmCam/")
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val uri = Uri.withAppendedPath(collection, id.toString())
                photos.add(uri)
            }
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
            val iv = ImageView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    400
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(2, 2, 2, 2)
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
