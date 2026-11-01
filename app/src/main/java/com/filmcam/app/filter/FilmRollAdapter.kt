package com.filmcam.app.filter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.filmcam.app.R

/**
 * 胶卷列表适配器
 */
class FilmRollAdapter(
    private val rolls: MutableList<FilmRoll>,
    private val onSelect: (FilmRoll) -> Unit
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
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_film_roll, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val roll = rolls[position]
        holder.chip.setImageResource(roll.iconResId)
        holder.name.text = roll.name
        holder.desc.text = roll.description
        holder.tag.text = roll.tag
        if (roll.available) {
            holder.itemView.alpha = 1f
            holder.itemView.setOnClickListener { onSelect(roll) }
        } else {
            // 反转片：列表保留但置灰。与原项目一致——引擎有这支胶卷，
            // 但本版只支持负片印相纸，选中即拒绝。
            holder.itemView.alpha = 0.32f
            holder.itemView.setOnClickListener {
                android.widget.Toast.makeText(
                    it.context,
                    "反转片（幻灯）不能印到相纸上——本版暂只支持负片",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun getItemCount() = rolls.size
}
