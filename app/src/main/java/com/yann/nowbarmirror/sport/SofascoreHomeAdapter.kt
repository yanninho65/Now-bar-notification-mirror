package com.yann.nowbarmirror.sport

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.yann.nowbarmirror.R

/**
 * Élément listé par MainActivity.showHomeState() : soit "dernière notif"
 * (auto), soit un match Sofascore précis. Type top-level (pas imbriqué
 * dans MainActivity comme avant la refonte du 16/09/2026) pour être
 * utilisable par [SofascoreHomeAdapter], dans un fichier séparé.
 */
sealed class SofascorePickerItem {
    object Latest : SofascorePickerItem()
    data class Match(val option: SofascoreMatchOption) : SofascorePickerItem()
}

/**
 * Adaptateur de l'écran d'accueil (liste des notifications Sofascore
 * actives, "Dernière notification (auto)" toujours en tête — voir
 * MainActivity.showHomeState()). Un tap sur la ligne sélectionne cet
 * élément comme repli Sofascore actif ([onRowClicked]) ; un tap sur le
 * bouton "API" (absent pour "Dernière notification") ouvre l'écran de
 * choix d'API pour CETTE notification précise ([onConfigureApi]) — voir
 * MainActivity.openApiPickerFor().
 *
 * [isActive] marque (préfixe "✓ ") l'élément qui pilote actuellement la
 * complication, et le bouton "API"/"API ✓" + le texte de la ligne
 * reflètent l'override éventuellement configuré (voir
 * SofascoreApiOverridePrefs) — demandé par Yann le 16/09/2026 ("mon choix
 * doit se voir clairement sur écran d'accueil").
 */
class SofascoreHomeAdapter(
    private val items: List<SofascorePickerItem>,
    private val isActive: (SofascorePickerItem) -> Boolean,
    private val onRowClicked: (SofascorePickerItem) -> Unit,
    private val onConfigureApi: (SofascoreMatchOption) -> Unit
) : RecyclerView.Adapter<SofascoreHomeAdapter.ViewHolder>() {

    class ViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        val image: ImageView = root.findViewById(R.id.itemImage)
        val text: TextView = root.findViewById(R.id.itemText)
        val apiButton: Button = root.findViewById(R.id.itemApiButton)
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
                holder.apiButton.visibility = View.GONE
                holder.apiButton.setOnClickListener(null)
            }
            is SofascorePickerItem.Match -> {
                val option = item.option
                val preview = option.latestLine.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
                val override = option.override
                val badge = override?.let { "\n🔗 API ${apiLabel(it.source)} (${fieldsLabel(it)})" } ?: ""
                holder.text.text = "$prefix${option.homeTeam} - ${option.awayTeam}$preview$badge"

                val bitmap = option.notifImage
                if (bitmap != null) {
                    holder.image.setImageBitmap(bitmap)
                    holder.image.visibility = View.VISIBLE
                } else {
                    holder.image.visibility = View.GONE
                }

                holder.apiButton.visibility = View.VISIBLE
                holder.apiButton.text = if (override != null) "API ✓" else "API"
                holder.apiButton.setOnClickListener { onConfigureApi(option) }
            }
        }

        holder.itemView.setOnClickListener { onRowClicked(item) }
    }

    override fun getItemCount(): Int = items.size

    private fun apiLabel(source: ApiSource) = if (source == ApiSource.LIVE_TENNIS) "Live Tennis" else "TheSportsDB"

    private fun fieldsLabel(override: SofascoreApiOverride): String = buildList {
        if (override.showScore) add("score")
        if (override.showPeriod) add("période")
    }.joinToString("+")
}
