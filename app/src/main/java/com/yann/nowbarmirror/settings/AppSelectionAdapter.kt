package com.yann.nowbarmirror.settings

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.yann.nowbarmirror.R

data class SelectableApp(
    val packageName: String,
    val label: String,
    val icon: Drawable
)

class AppSelectionAdapter(
    private val context: Context,
    private val apps: List<SelectableApp>
) : RecyclerView.Adapter<AppSelectionAdapter.ViewHolder>() {

    // Kept for accessibility: full-length labels ("Aucun" / "Dernière notif" / "Toutes"),
    // used as contentDescription on the compact mode buttons below.
    private val modeLabels = context.resources.getStringArray(R.array.mirror_mode_labels)

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val container: LinearLayout = view.findViewById(R.id.row_container)
        val icon: ImageView = view.findViewById(R.id.app_icon)
        val label: TextView = view.findViewById(R.id.app_label)
        val toggleGroup: MaterialButtonToggleGroup = view.findViewById(R.id.mode_toggle_group)
        val btnNone: MaterialButton = view.findViewById(R.id.btn_mode_none)
        val btnLatest: MaterialButton = view.findViewById(R.id.btn_mode_latest)
        val btnAll: MaterialButton = view.findViewById(R.id.btn_mode_all)
        val invertCheckbox: CheckBox = view.findViewById(R.id.invert_checkbox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_selectable_app, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount() = apps.size

    private fun buttonIdFor(mode: MirrorMode) = when (mode) {
        MirrorMode.NONE -> R.id.btn_mode_none
        MirrorMode.LATEST -> R.id.btn_mode_latest
        MirrorMode.ALL -> R.id.btn_mode_all
    }

    private fun modeForButtonId(id: Int) = when (id) {
        R.id.btn_mode_none -> MirrorMode.NONE
        R.id.btn_mode_latest -> MirrorMode.LATEST
        else -> MirrorMode.ALL
    }

    /**
     * Makes it obvious at a glance which apps are actively mirrored: an active row (mode
     * != NONE) gets the accent-tinted card background at full opacity, an inactive row
     * (mode == NONE) stays on the plain/neutral background and its icon+label are dimmed.
     */
    private fun applyRowState(holder: ViewHolder, mode: MirrorMode) {
        val active = mode != MirrorMode.NONE
        holder.container.setBackgroundResource(
            if (active) R.drawable.bg_app_row_active else R.drawable.bg_app_row_inactive
        )
        val alpha = if (active) 1f else 0.6f
        holder.icon.alpha = alpha
        holder.label.alpha = alpha
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.icon.setImageDrawable(app.icon)
        holder.label.text = app.label
        holder.btnNone.contentDescription = modeLabels.getOrNull(0)
        holder.btnLatest.contentDescription = modeLabels.getOrNull(1)
        holder.btnAll.contentDescription = modeLabels.getOrNull(2)

        // Clear any listener from a recycled holder before restoring the saved selection,
        // so setting it back doesn't immediately re-trigger a write.
        holder.toggleGroup.clearOnButtonCheckedListeners()

        val currentMode = AppMirrorPrefs.getMode(context, app.packageName)
        holder.toggleGroup.check(buttonIdFor(currentMode))
        applyRowState(holder, currentMode)

        holder.toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newMode = modeForButtonId(checkedId)
            AppMirrorPrefs.setMode(context, app.packageName, newMode)
            applyRowState(holder, newMode)
        }

        // Same detach/restore/reattach dance as the toggle group above, so restoring the
        // saved value doesn't immediately re-trigger a write.
        holder.invertCheckbox.setOnCheckedChangeListener(null)
        holder.invertCheckbox.isChecked = AppMirrorPrefs.getInvertTitleText(context, app.packageName)
        holder.invertCheckbox.setOnCheckedChangeListener { _, isChecked ->
            AppMirrorPrefs.setInvertTitleText(context, app.packageName, isChecked)
        }
    }
}
