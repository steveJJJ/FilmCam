package com.filmcam.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.filmcam.app.develop.ExifReader
import com.filmcam.app.film.FlashCalibStore
import com.filmcam.app.film.ShotMeta
import com.filmcam.app.film.UndevelopedFilmStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 外闪手动标定样本列表（11-22，11-22 夜重构）。
 *
 * 🔴数据源纠正：最初这里只扫 MediaStore 里 IS_PENDING=0 的**已冲洗成片 EXIF**，
 * 于是「拍了但样本库里没有」是必然结果 —— 刚拍的帧是 IS_PENDING=1 的未冲洗底片，
 * 暗房 v2 又是「冲洗→母片→编辑保存才出成片」，EXIF UserComment.shot 要等到最后
 * 一步才有。用户要的是「拍完就能挑样本」，不是「先冲一卷再来标记」。
 *
 * 现在第一数据源 = 卷登记里每一帧的 shotJson（落卷时 shotJsonWithCapture() 已把
 * calibId / 功率 / 距离 / 环境基线 / p50 全部写进去），拍完立刻可见、与成片一一对应；
 * 已冲洗成片 EXIF 只作为补充（兼容导入照片、登记被清理的老片），按 calibId 去重。
 * 标记直接写列表行里的按钮，不再依赖看图页的展开信息面板。
 */
class CalibListActivity : AppCompatActivity() {

    private data class Sample(
        val calibId: Long,
        val meta: ShotMeta,
        val viewUri: String,
        val thumb: Bitmap?,
        val status: String,
    )

    private val samples = mutableListOf<Sample>()
    private lateinit var adapter: CalibAdapter
    /** 防快速进出时两次扫描竞态：只有最新一次 load 允许回写列表。 */
    @Volatile private var loadGen = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 列表可能从暗房冷启动直达，init 幂等，保证 prefs 一定可用。
        UndevelopedFilmStore.init(applicationContext)
        setContentView(R.layout.activity_calib_list)
        findViewById<View>(R.id.btnCalibBack)?.setOnClickListener { finish() }
        adapter = CalibAdapter(samples, { pos -> openAt(pos) }, { pos, mark -> setMark(pos, mark) })
        findViewById<RecyclerView>(R.id.rvCalib).apply {
            layoutManager = LinearLayoutManager(this@CalibListActivity)
            this.adapter = this@CalibListActivity.adapter
        }
    }

    /** 从看图页标记完返回时刷新，列表标记与看图页保持同一份文件。 */
    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        // 资源串在主线程取一次，IO 线程只做解析/解码。
        val labelRaw = getString(R.string.calib_frame_raw)
        val labelDeveloped = getString(R.string.calib_frame_developed)
        val gen = ++loadGen
        CoroutineScope(Dispatchers.IO).launch {
            val found = mutableListOf<Sample>()

            // ① 补充源：已冲洗成片 EXIF（含从外部导入、卷登记已不存在的老片）。
            //    拍照刚落卷的帧是 IS_PENDING=1，这里查不到 —— 所以它不是主数据源。
            val developed = mutableMapOf<Long, Pair<String, ShotMeta>>()
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val selection: String
            val args: Array<String>
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                selection = "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND " +
                    "${MediaStore.Images.Media.IS_PENDING} = 0"
                args = arrayOf("DCIM/FilmCam/")
            } else {
                selection = "${MediaStore.Images.Media.DATA} LIKE ?"
                args = arrayOf("%/DCIM/FilmCam/%")
            }
            try {
                contentResolver.query(
                    collection, projection, selection, args,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    while (c.moveToNext()) {
                        val uriString = Uri.withAppendedPath(
                            collection, c.getLong(idCol).toString()
                        ).toString()
                        val shot = ExifReader.read(this@CalibListActivity, Uri.parse(uriString))?.shot
                            ?: continue
                        if (shot.calibId > 0L) developed[shot.calibId] = uriString to shot
                    }
                }
            } catch (_: Exception) {}

            // ② 主数据源：卷登记的全部帧（未冲洗 / 已冲洗 / 待编辑都在）。
            //    calibId 只在 EXTERNAL 档分配，所以自动把非外闪帧滤掉。
            val allFrames = UndevelopedFilmStore.allFrames()
            val frames = allFrames
                .mapNotNull { f -> ShotMeta.fromJson(f.shotJson)?.let { f to it } }
                .filter { (_, m) -> m.calibId > 0L }
                .sortedByDescending { (f, _) -> f.takenAtMs }

            for ((f, m) in frames) {
                val done = developed.containsKey(m.calibId)
                val frameUri = handleUri(f.handle)?.toString().orEmpty()
                found += Sample(
                    calibId = m.calibId,
                    meta = m,
                    // 已出成片就看成片（带完整 EXIF），否则看直出底片。
                    viewUri = if (done) developed.getValue(m.calibId).first else frameUri,
                    thumb = decodeThumb(this@CalibListActivity, f.handle),
                    status = if (done) labelDeveloped else labelRaw,
                )
            }

            // ③ 只在成片 EXIF 里、卷登记已无对应帧的样本（导入的老片）补进来。
            val seen = found.map { it.calibId }.toHashSet()
            developed.forEach { (id, pair) ->
                if (id in seen) return@forEach
                found += Sample(
                    calibId = id,
                    meta = pair.second,
                    viewUri = pair.first,
                    thumb = decodeThumb(this@CalibListActivity, pair.first),
                    status = labelDeveloped,
                )
            }

            withContext(Dispatchers.Main) {
                if (gen != loadGen) return@withContext
                samples.clear()
                samples.addAll(found)
                adapter.notifyDataSetChanged()
                findViewById<View>(R.id.rvCalib).visibility =
                    if (found.isEmpty()) View.GONE else View.VISIBLE
                val emptyView = findViewById<TextView>(R.id.tvCalibEmpty)
                emptyView.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
                if (found.isEmpty()) {
                    // 卷里有帧却一张 EXT 都没有 → 档位问题，不要让用户继续盲拍。
                    emptyView.text = getString(
                        if (allFrames.isEmpty()) R.string.calib_list_empty
                        else R.string.calib_list_no_ext
                    )
                }
                findViewById<TextView>(R.id.tvCalibCount).text =
                    getString(R.string.calib_list_count, found.size)
            }
        }
    }

    /** API29+ 帧 handle 是 MediaStore content uri；API≤28 是绝对路径。 */
    private fun handleUri(raw: String): Uri? = when {
        raw.isBlank() -> null
        raw.startsWith("content://") -> Uri.parse(raw)
        raw.startsWith("/") -> Uri.fromFile(File(raw))
        else -> null
    }

    /** 行内缩略图：直出底片（IS_PENDING=1 对本应用仍可读），1/16 采样足够看曝光好坏。 */
    private fun decodeThumb(ctx: Context, raw: String): Bitmap? {
        val uri = handleUri(raw) ?: return null
        return runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(
                    input, null,
                    BitmapFactory.Options().apply {
                        inSampleSize = 16
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                )
            }
        }.getOrNull()
    }

    private fun setMark(pos: Int, mark: String?) {
        if (pos !in samples.indices) return
        FlashCalibStore.write(this, samples[pos].calibId, mark)
        com.filmcam.app.ui.Haptics.vibrate(this, 14, 140)
        adapter.notifyItemChanged(pos)
    }

    private fun openAt(pos: Int) {
        if (pos !in samples.indices) return
        com.filmcam.app.ui.Haptics.vibrate(this, 18, 150)
        startActivity(Intent(this, PhotoViewerActivity::class.java).apply {
            putStringArrayListExtra(
                PhotoViewerActivity.EXTRA_URIS,
                ArrayList(samples.map { it.viewUri })
            )
            // 未冲洗底片没有 UserComment.shot，看图页靠这份快照也能显示标定行 + 标记。
            putStringArrayListExtra(
                PhotoViewerActivity.EXTRA_CALIB_SHOTS,
                ArrayList(samples.map { it.meta.toJson() })
            )
            putExtra(PhotoViewerActivity.EXTRA_POSITION, pos)
        })
    }

    private inner class CalibAdapter(
        private val items: List<Sample>,
        private val onClick: (Int) -> Unit,
        private val onMark: (Int, String?) -> Unit,
    ) : RecyclerView.Adapter<CalibAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val id: TextView = v.findViewById(R.id.tvCalibId)
            val mark: TextView = v.findViewById(R.id.tvCalibMark)
            val params: TextView = v.findViewById(R.id.tvCalibParams)
            val thumb: ImageView = v.findViewById(R.id.ivCalibThumb)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_calib_row, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = items[position]
            holder.id.text = "标定 #${s.calibId}"
            val m = FlashCalibStore.read(this@CalibListActivity, s.calibId)
            holder.mark.text = when (m) {
                FlashCalibStore.GOOD -> "✓ 好样本"
                FlashCalibStore.BAD -> "✗ 差样本"
                else -> getString(R.string.calib_mark_none)
            }
            holder.mark.setTextColor(
                when (m) {
                    FlashCalibStore.GOOD -> 0xFF7FD4A0.toInt()
                    FlashCalibStore.BAD -> 0xFFE0A08A.toInt()
                    else -> 0xFFC9C5BC.toInt()
                }
            )
            val parts = mutableListOf<String>()
            if (s.meta.calibPwr >= 0) {
                parts += "功率 1/${com.filmcam.app.flash.A2PowerProtocol.denominator(s.meta.calibPwr)}"
            }
            parts += if (s.meta.calibManual) "手动档" else "自动档"
            if (s.meta.sensorIso > 0) parts += "ISO ${s.meta.sensorIso}"
            if (s.meta.sensorShutterSec > 0f) {
                parts += if (s.meta.sensorShutterSec >= 1f) {
                    String.format(java.util.Locale.US, "%.2fs", s.meta.sensorShutterSec)
                } else "1/${kotlin.math.round(1f / s.meta.sensorShutterSec).toInt()}s"
            }
            if (s.meta.calibDist > 0f) {
                parts += String.format(java.util.Locale.US, "%.2fm", s.meta.calibDist)
            }
            if (s.meta.calibAmbIso > 0) parts += "环境 ISO ${s.meta.calibAmbIso}"
            if (s.meta.calibAmbSec > 0f) {
                parts += String.format(java.util.Locale.US, "环境 1/%ds",
                    kotlin.math.round(1f / s.meta.calibAmbSec).toInt())
            }
            if (s.meta.calibP50 > 0) parts += "p50=${s.meta.calibP50}"
            parts += s.status
            holder.params.text = parts.joinToString(" · ")

            if (s.thumb != null) holder.thumb.setImageBitmap(s.thumb)
            else holder.thumb.setImageResource(android.R.drawable.ic_menu_gallery)

            holder.itemView.setOnClickListener {
                val p = holder.adapterPosition
                if (p != RecyclerView.NO_POSITION) onClick(p)
            }
            holder.itemView.findViewById<View>(R.id.btnCalibRowGood).setOnClickListener {
                val p = holder.adapterPosition
                if (p != RecyclerView.NO_POSITION) onMark(p, FlashCalibStore.GOOD)
            }
            holder.itemView.findViewById<View>(R.id.btnCalibRowBad).setOnClickListener {
                val p = holder.adapterPosition
                if (p != RecyclerView.NO_POSITION) onMark(p, FlashCalibStore.BAD)
            }
            holder.itemView.findViewById<View>(R.id.btnCalibRowClear).setOnClickListener {
                val p = holder.adapterPosition
                if (p != RecyclerView.NO_POSITION) onMark(p, null)
            }
        }

        override fun getItemCount() = items.size
    }
}
