package com.yann.nowbarmirror.sport

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Adaptateur générique pour une simple liste "nom + clic" — remplace
 * l'ancien TeamsAdapter (spécifique à TeamResult) maintenant que la
 * recherche gère aussi les joueurs et les ligues, qui partagent la même
 * présentation à l'écran (un nom, un clic).
 *
 * [image] est optionnel (`null` par défaut, comme pour les recherches
 * équipe/joueur/ligue, qui n'ont rien à montrer). Quand fourni et que le
 * Bitmap renvoyé pour un item n'est pas `null`, une vignette apparaît à
 * gauche du texte de cette ligne — utilisé pour l'instant uniquement par
 * le sélecteur de repli Sofascore (MainActivity.showSofascorePicker),
 * pour vérifier visuellement quelle image
 * SofascoreNotificationListenerService.extractNotificationImage extrait
 * réellement de chaque notif active, avant de s'appuyer dessus côté
 * montre.
 */
class SimpleListAdapter<T>(
    private val items: List<T>,
    private val displayName: (T) -> String,
    private val image: ((T) -> Bitmap?)? = null,
    private val onClicked: (T) -> Unit
) : RecyclerView.Adapter<SimpleListAdapter.ViewHolder>() {

    class ViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        val imageView: ImageView = root.findViewById(R.id.itemImage)
        val textView: TextView = root.findViewById(R.id.itemText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_team, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.textView.text = displayName(item)

        val bitmap = image?.invoke(item)
        if (bitmap != null) {
            holder.imageView.setImageBitmap(bitmap)
            holder.imageView.visibility = View.VISIBLE
        } else {
            holder.imageView.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onClicked(item) }
    }

    override fun getItemCount(): Int = items.size
}
