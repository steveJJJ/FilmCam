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
    private val rolls: List<FilmRoll>,
    private val onSelect: (FilmRoll) -> Unit
) : RecyclerView.Adapter<FilmRollAdapter.VH>() {

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
        holder.itemView.setOnClickListener { onSelect(roll) }
    }

    override fun getItemCount() = rolls.size
}
