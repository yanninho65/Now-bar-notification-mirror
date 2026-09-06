package com.yann.nowbarmirror.settings

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
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

    private val modeLabels = context.resources.getStringArray(R.array.mirror_mode_labels)

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.app_icon)
        val label: TextView = view.findViewById(R.id.app_label)
        val modeSpinner: Spinner = view.findViewById(R.id.mode_spinner)
        val invertCheckbox: CheckBox = view.findViewById(R.id.invert_checkbox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_selectable_app, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount() = apps.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.icon.setImageDrawable(app.icon)
        holder.label.text = app.label

        // Detach the listener while we set the initial selection, so restoring
        // the saved mode doesn't immediately re-trigger a write.
        holder.modeSpinner.onItemSelectedListener = null

        val spinnerAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, modeLabels)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        holder.modeSpinner.adapter = spinnerAdapter

        val currentMode = AppMirrorPrefs.getMode(context, app.packageName)
        holder.modeSpinner.setSelection(currentMode.ordinal, false)

        holder.modeSpinner.post {
            holder.modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    AppMirrorPrefs.setMode(context, app.packageName, MirrorMode.values()[pos])
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }

        // Same detach/restore/reattach dance as the spinner above, so restoring the saved
        // value doesn't immediately re-trigger a write.
        holder.invertCheckbox.setOnCheckedChangeListener(null)
        holder.invertCheckbox.isChecked = AppMirrorPrefs.getInvertTitleText(context, app.packageName)
        holder.invertCheckbox.setOnCheckedChangeListener { _, isChecked ->
            AppMirrorPrefs.setInvertTitleText(context, app.packageName, isChecked)
        }
    }
}
