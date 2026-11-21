package com.filmcam.app.filter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.filmcam.app.R
import com.filmcam.app.ui.Motion
import com.filmcam.app.ui.attachRowPress

/**
 * 胶卷列表适配器
 *
 * 11-21 换卷体验：当前装机卷加琥珀铭牌 + 图标描边；点选走「卡入」微动再回调；
 * 被拒（当前卷有未冲洗帧）走「顶回」抖动；列表项统一暖白按压高亮。🔴反转片一律可选可拍。
 */
class FilmRollAdapter(
    private val rolls: MutableList<FilmRoll>,
    private val currentName: String?,
    /** 点选时先播卡入微动，动画结束再回调（由调用方决定 dismiss）。 */
    private val onPickAnimationEnd: (FilmRoll) -> Unit,
    /** 被拒时播顶回抖动，动画结束再回调（调用方只提示、不 dismiss）。 */
    private val onRejectAnimationEnd: (FilmRoll) -> Unit,
    /** 这一支当前能不能换（未冲洗帧拦截），与 selectFilmRoll 的拦截口径一致。 */
    private val canPick: (FilmRoll) -> Boolean,
) : RecyclerView.Adapter<FilmRollAdapter.VH>() {

    /** 长按拖拽用：把 from 位的胶卷移到 to 位，并通知 RecyclerView 播移动动画。 */
    fun moveItem(from: Int, to: Int) {
        if (from == to || from !in rolls.indices || to !in rolls.indices) return
        val item = rolls.removeAt(from)
        rolls.add(to, item)
        notifyItemMoved(from, to)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val chip: ImageView = view.findViewById(R.id.viewColorChip)
        val name: TextView = view.findViewById(R.id.tvRollName)
        val desc: TextView = view.findViewById(R.id.tvRollDesc)
        val tag: TextView = view.findViewById(R.id.tvRollTag)
        val badge: TextView = view.findViewById(R.id.tvRollCurrent)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_film_roll, parent, false)
        // 列表项是 RecyclerView 运行时 inflate 出来的，页面级 applyToViewTree 刷不到它们
        // （那时列表还是空的），所以每个 item 建好时自己刷一遍机身配色。
        com.filmcam.app.ui.BodyTheme.applyToViewTree(view)
        // P2：和设置行 / 控制台行同一套暖白按压高亮
        view.attachRowPress()
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val roll = rolls[position]
        holder.chip.setImageResource(roll.iconResId)
        holder.name.text = roll.name
        holder.desc.text = roll.description
        // v16：右侧优先显示适用场景；没配场景的老数据回落分类标签
        holder.tag.text = roll.scene.ifBlank { roll.tag }

        // P0：当前装机卷一眼可辨 —— 琥珀「装机中」铭牌 + 图标描边
        val isCurrent = roll.name == currentName
        holder.badge.visibility = if (isCurrent) View.VISIBLE else View.GONE
        if (isCurrent) {
            val dp = holder.itemView.resources.displayMetrics.density
            holder.chip.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 4f * dp
                setStroke((2f * dp).toInt(), 0xFFFFC100.toInt())
            }
        } else {
            holder.chip.background = null
        }

        // 列表项一律可选态：拦截由 canPick 统一判定（点选时走顶回抖动 + LCD 提示）。
        // 🔴不按胶卷类型置灰 —— 反转片是一等胶卷，必须可选、可拍摄。
        holder.itemView.alpha = 1f

        holder.itemView.setOnClickListener {
            if (!canPick(roll)) {
                // 拦截：顶回抖动，不 dismiss；回调里给 LCD 提示 + 双震
                bumpBack(holder.itemView) { onRejectAnimationEnd(roll) }
                return@setOnClickListener
            }
            // 选中：先卡入微动，结束再回调（调用方 dismiss + 换卷）
            snapIn(holder.itemView) { onPickAnimationEnd(roll) }
        }
    }

    /** 选中卡入：整行短促下沉再回弹（PRESS_DOWN accelerate + PRESS_UP decelerate）。 */
    private fun snapIn(view: View, onEnd: () -> Unit) {
        val dp = view.resources.displayMetrics.density
        val a = android.animation.ValueAnimator.ofFloat(0f, 2.5f * dp).apply {
            duration = Motion.duration(view.context, Motion.PRESS_DOWN)
            interpolator = Motion.accelerate
        }
        val b = android.animation.ValueAnimator.ofFloat(2.5f * dp, 0f).apply {
            duration = Motion.duration(view.context, Motion.PRESS_UP)
            interpolator = Motion.decelerate
        }
        a.addUpdateListener { view.translationY = it.animatedValue as Float }
        b.addUpdateListener { view.translationY = it.animatedValue as Float }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) { b.start() }
        })
        b.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                view.translationY = 0f
                onEnd()
            }
        })
        a.start()
    }

    /** 拦截顶回：左右 ±3dp 短促抖两下，像机身顶住了。 */
    private fun bumpBack(view: View, onEnd: () -> Unit) {
        val dp = view.resources.displayMetrics.density
        val a = android.animation.ValueAnimator.ofFloat(0f, 3f * dp, -2f * dp, 0f).apply {
            duration = Motion.duration(view.context, Motion.TICK) * 2
            interpolator = Motion.decelerate
        }
        a.addUpdateListener { view.translationX = it.animatedValue as Float }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                view.translationX = 0f
                onEnd()
            }
        })
        a.start()
    }

    override fun getItemCount() = rolls.size
}
