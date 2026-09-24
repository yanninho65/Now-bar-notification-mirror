package com.yann.nowbarmirror.sport

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.yann.nowbarmirror.R

/** Row of SportActivity: either "latest notification" (auto) or one precise Sofascore match. */
sealed class SofascorePickerItem {
    object Latest : SofascorePickerItem()
    data class Match(val option: SofascoreMatchOption) : SofascorePickerItem()
}

/**
 * SportActivity's list. Tapping a row makes it the match followed by the watch; [isActive] marks
 * ("✓ ") the one currently driving the complication.
 */
class SofascoreHomeAdapter(
    private val items: List<SofascorePickerItem>,
    private val isActive: (SofascorePickerItem) -> Boolean,
    private val onRowClicked: (SofascorePickerItem) -> Unit
) : RecyclerView.Adapter<SofascoreHomeAdapter.ViewHolder>() {

    class ViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        val image: ImageView = root.findViewById(R.id.itemImage)
        val text: TextView = root.findViewById(R.id.itemText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sofascore_match, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val prefix = if (isActive(item)) "✓ " else ""

        when (item) {
            is SofascorePickerItem.Latest -> {
                holder.text.text = "${prefix}Dernière notification (auto)"
                holder.image.visibility = View.GONE
            }
            is SofascorePickerItem.Match -> {
                val option = item.option
                val preview = option.latestLine.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
                holder.text.text = "$prefix${option.homeTeam} - ${option.awayTeam}$preview"
                val bitmap = option.notifImage
                if (bitmap != null) {
                    holder.image.setImageBitmap(bitmap)
                    holder.image.visibility = View.VISIBLE
                } else {
                    holder.image.visibility = View.GONE
                }
            }
        }

        holder.itemView.setOnClickListener { onRowClicked(item) }
    }

    override fun getItemCount(): Int = items.size
}
