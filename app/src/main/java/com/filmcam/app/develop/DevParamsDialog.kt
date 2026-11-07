package com.filmcam.app.develop

import android.content.Context
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import app.spektralab.engine.FilmStockMapper
import com.filmcam.app.R
import com.filmcam.app.filter.FilmRoll
import com.filmcam.app.filter.FilmRollRepository
import kotlin.math.roundToInt

/**
 * 冲洗参数（按胶卷分别存的开发用旋钮）：颗粒 / 辉光 / 眩光 / 曝光补偿 / 相纸。
 *
 * 这几个参数是工程师模式的一部分 —— 它们会直接改引擎的输出来，普通使用不该看到，
 * 所以从设置面板搬到了工程师控制台。为了能被关于页（工程师模式入口）直接打开，
 * 这里做成与 Activity 无耦合的独立入口：调用方只需要给出「默认选中哪支胶卷」。
 *
 * 存储键形如 `filmcam_dev_<param>_<stockId>`，缺省即回落引擎/相纸的冻结默认值，
 * 「恢复默认」就是把这几个后缀键删掉。
 */
object DevParamsDialog {

    /**
     * @param defaultStockId 打开时默认选中的胶卷 stock id（一般传当前装的那支）；
     *                       传 null 或引擎不认识时回退到第一支。
     * @param onClose 关闭回调（工程师控制台用它把自己重新拉起来）。
     */
    fun show(
        activity: AppCompatActivity,
        defaultStockId: String?,
        onClose: (() -> Unit)? = null,
    ) {
        val prefs = activity.getSharedPreferences("filmcam", Context.MODE_PRIVATE)
        val view = activity.layoutInflater.inflate(R.layout.dialog_dev_params, null)
        // 弹层内部的文字/面板也跟随机身配色（XML 里是深底浅字的写法）
        com.filmcam.app.ui.BodyTheme.applyToViewTree(view)

        val sbGrain = view.findViewById<SeekBar>(R.id.sbDevGrain)
        val sbHalation = view.findViewById<SeekBar>(R.id.sbDevHalation)
        val sbGlare = view.findViewById<SeekBar>(R.id.sbDevGlare)
        val tvGrain = view.findViewById<android.widget.TextView>(R.id.tvDevGrainValue)
        val tvHalation = view.findViewById<android.widget.TextView>(R.id.tvDevHalationValue)
        val tvGlare = view.findViewById<android.widget.TextView>(R.id.tvDevGlareValue)
        val sbExposure = view.findViewById<SeekBar>(R.id.sbDevExposure)
        val tvExposure = view.findViewById<android.widget.TextView>(R.id.tvDevExposureValue)
        val rowPaper = view.findViewById<android.view.View>(R.id.rowDevPaper)
        val tvPaper = view.findViewById<android.widget.TextView>(R.id.tvDevPaperValue)
        val rowRoll = view.findViewById<android.view.View>(R.id.rowDevRoll)
        val tvRoll = view.findViewById<android.widget.TextView>(R.id.tvDevRollValue)
        val btnReset = view.findViewById<android.widget.Button>(R.id.btnDevReset)
        btnReset.text = activity.getString(R.string.main_dev_reset)

        // 相纸选项：第一项自动（""），其后 9 款 catalog prints
        val paperOptions = try {
            FilmStockMapper.printStockOptions(activity)
        } catch (_: Throwable) {
            emptyList()
        }
        fun paperDisplayName(id: String): String =
            if (id.isEmpty()) activity.getString(R.string.main_dev_paper_auto)
            else paperOptions.firstOrNull { it.id == id }?.name ?: id

        // 当前选中的胶卷 stock id（默认由调用方给；不在 9 款映射时回退第一款）
        fun stockIdOf(roll: FilmRoll): String? =
            try { FilmStockMapper.filmStockIdFor(roll.name) } catch (_: Throwable) { null }
        var selRollId: String = defaultStockId?.takeIf { it.isNotBlank() }
            ?: stockIdOf(FilmRollRepository.rolls.firstOrNull() ?: return)
            ?: ""
        // 后缀键：filmcam_dev_<param>_<stockId>
        fun devKey(param: String) = "filmcam_dev_${param}_$selRollId"

        // SeekBar progress = (EV + 1.5) * 100；grain/halation 0..2.00，glare 0..6.00；
        // exposure 是**输入层**曝光补偿 −1.50..+1.50 EV（正 = 更亮）。
        // 老版本这里调的是放大机曝光（print_exposure），那会把片框一起冲浓冲淡，已废弃。
        fun syncUi() {
            tvRoll.text = try { FilmStockMapper.stockDisplayName(selRollId) } catch (_: Throwable) { selRollId }
            sbGrain.progress = (prefs.getFloat(devKey("grain"), 1.0f) * 100).toInt()
            sbHalation.progress = (prefs.getFloat(devKey("halation"), 1.0f) * 100).toInt()
            sbGlare.progress = (prefs.getFloat(devKey("glare"), 3.0f) * 100).toInt()
            tvGrain.text = String.format("%.2f", sbGrain.progress / 100f)
            tvHalation.text = String.format("%.2f", sbHalation.progress / 100f)
            tvGlare.text = String.format("%.2f", sbGlare.progress / 100f)
            val ev = prefs.getFloat(devKey("exposure_ev"), 0f)
                .coerceIn(FilmStockMapper.INPUT_EV_MIN, FilmStockMapper.INPUT_EV_MAX)
            sbExposure.progress = ((ev - FilmStockMapper.INPUT_EV_MIN) * 100f).roundToInt()
            tvExposure.text = String.format("%+.2f", ev)
            tvPaper.text = paperDisplayName(prefs.getString(devKey("paper"), "") ?: "")
            // 反转片没有印相纸环节（引擎 scan_film 直扫正片）：相纸行置灰
            val positiveStock = FilmStockMapper.isPositive(selRollId)
            rowPaper.isEnabled = !positiveStock
            rowPaper.alpha = if (positiveStock) 0.45f else 1f
            if (positiveStock) tvPaper.text = activity.getString(R.string.main_dev_slide_no_paper)
        }
        syncUi()

        // 切胶卷：单选 9 款 FilmRoll
        rowRoll.setOnClickListener {
            val rolls = FilmRollRepository.rolls
            val items = arrayOfNulls<String>(rolls.size)
            val ids = arrayOfNulls<String>(rolls.size)
            rolls.forEachIndexed { i, r ->
                val id = stockIdOf(r) ?: ""
                ids[i] = id
                val disp = try { FilmStockMapper.stockDisplayName(id) } catch (_: Throwable) { id }
                items[i] = "$disp ($id)"
            }
            val checked = ids.indexOf(selRollId).coerceAtLeast(0)
            AlertDialog.Builder(activity, com.filmcam.app.ui.BodyTheme.dialogTheme)
                .setTitle(activity.getString(R.string.main_pick_film_title))
                .setSingleChoiceItems(items, checked) { d, which ->
                    selRollId = ids[which] ?: ""
                    d.dismiss()
                    syncUi()
                }
                .setNegativeButton(activity.getString(R.string.main_cancel), null)
                .show()
        }

        sbGrain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress / 100f
                prefs.edit().putFloat(devKey("grain"), v).apply()
                tvGrain.text = String.format("%.2f", v)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        sbHalation.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress / 100f
                prefs.edit().putFloat(devKey("halation"), v).apply()
                tvHalation.text = String.format("%.2f", v)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        sbGlare.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress / 100f
                prefs.edit().putFloat(devKey("glare"), v).apply()
                tvGlare.text = String.format("%.2f", v)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        sbExposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = FilmStockMapper.INPUT_EV_MIN + progress / 100f
                prefs.edit().putFloat(devKey("exposure_ev"), v).apply()
                tvExposure.text = String.format("%+.2f", v)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 相纸选择：单选弹窗（自动 + 9 款）
        rowPaper.setOnClickListener {
            val current = prefs.getString(devKey("paper"), "") ?: ""
            val items = arrayOfNulls<String>(paperOptions.size + 1)
            items[0] = activity.getString(R.string.main_dev_paper_auto)
            paperOptions.forEachIndexed { i, opt -> items[i + 1] = opt.name }
            val checked = if (current.isEmpty()) 0 else (paperOptions.indexOfFirst { it.id == current } + 1).coerceAtLeast(0)
            AlertDialog.Builder(activity, com.filmcam.app.ui.BodyTheme.dialogTheme)
                .setTitle(activity.getString(R.string.main_pick_paper_title))
                .setSingleChoiceItems(items, checked) { d, which ->
                    val id = if (which == 0) "" else paperOptions[which - 1].id
                    prefs.edit().putString(devKey("paper"), id).apply()
                    tvPaper.text = paperDisplayName(id)
                    d.dismiss()
                }
                .setNegativeButton(activity.getString(R.string.main_cancel), null)
                .show()
        }

        // 恢复该款默认：移除当前 stockId 对应的 5 个后缀键（=回落冻结默认）
        btnReset.setOnClickListener {
            prefs.edit()
                .remove(devKey("grain"))
                .remove(devKey("halation"))
                .remove(devKey("glare"))
                .remove(devKey("exposure_ev"))
                // 旧版「放大机曝光」键一起清掉：它读出来已经不用了，留着只会误导下一次排查
                .remove(devKey("print_exposure"))
                .remove(devKey("paper"))
                .apply()
            syncUi()
        }

        AlertDialog.Builder(activity, com.filmcam.app.ui.BodyTheme.dialogTheme)
            .setTitle(activity.getString(R.string.set_dev))
            .setView(view)
            .setPositiveButton(activity.getString(R.string.main_ok)) { d, _ ->
                d.dismiss()
                onClose?.invoke()
            }
            .show()
    }
}
