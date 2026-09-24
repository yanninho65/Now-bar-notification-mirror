package com.yann.nowbarmirror.settings

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.yann.nowbarmirror.MirrorNotificationListener
import com.yann.nowbarmirror.R

/**
 * Picks the apps whose notifications feed the watch "Messages" complication ([MessageAppsPrefs]).
 * UPDATED 24/09/2026: selected apps come first, in Yann's order, with ▲/▼ to reorder them — the
 * watch's app rows follow this same order. Unselected launcher apps follow alphabetically.
 * Every change is saved at once and re-pushes the list to the watch.
 */
class MessageAppsActivity : AppCompatActivity() {

    private data class AppRow(val packageName: String, val label: String, val icon: Drawable)

    private lateinit var content: LinearLayout
    private lateinit var installed: Map<String, AppRow>
    private val order = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.one_ui_background))
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        installed = loadApps().associateBy { it.packageName }
        // Keep the saved order, but only for apps still installed with a launcher icon.
        order += MessageAppsPrefs.getOrdered(this).filter { it in installed }
        render()
    }

    private fun render() {
        content.removeAllViews()
        content.addView(text(getString(R.string.message_apps_title), 22f, R.color.one_ui_text_primary, top = 8, bottom = 4))
        content.addView(text(getString(R.string.message_apps_hint), 14f, R.color.one_ui_text_secondary, bottom = 12))

        if (order.isNotEmpty()) {
            content.addView(text(getString(R.string.message_apps_order_label), 13f, R.color.one_ui_text_secondary, top = 4, bottom = 4))
            order.forEachIndexed { index, pkg ->
                installed[pkg]?.let { content.addView(selectedRow(it, index)) }
            }
        }

        val others = installed.values.filter { it.packageName !in order }.sortedBy { it.label.lowercase() }
        if (others.isNotEmpty()) {
            content.addView(text(getString(R.string.message_apps_others_label), 13f, R.color.one_ui_text_secondary, top = 16, bottom = 4))
            others.forEach { content.addView(checkRow(it, checked = false)) }
        }
    }

    /** Selected app: checkbox + ▲/▼ to move it in the order. */
    private fun selectedRow(app: AppRow, index: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_app_row_active)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(3)
            bottomMargin = dp(3)
        }
        addView(checkRow(app, checked = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(arrow("▲", enabled = index > 0) { move(index, index - 1) })
        addView(arrow("▼", enabled = index < order.lastIndex) { move(index, index + 1) })
    }

    private fun checkRow(app: AppRow, checked: Boolean): CheckBox = CheckBox(this).apply {
        text = app.label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(getColor(R.color.one_ui_text_primary))
        gravity = Gravity.CENTER_VERTICAL
        app.icon.setBounds(0, 0, dp(32), dp(32))
        setCompoundDrawablesRelative(null, null, app.icon, null)
        compoundDrawablePadding = dp(12)
        setPadding(dp(8), dp(10), dp(8), dp(10))
        isChecked = checked
        setOnCheckedChangeListener { _, isNowChecked ->
            if (isNowChecked) order.add(app.packageName) else order.remove(app.packageName)
            save()
            content.post { render() }
        }
    }

    private fun arrow(symbol: String, enabled: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        text = symbol
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTextColor(getColor(if (enabled) R.color.one_ui_primary else R.color.one_ui_stroke))
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isEnabled = enabled
        if (enabled) setOnClickListener { onClick() }
    }

    private fun move(from: Int, to: Int) {
        if (to !in order.indices) return
        order.add(to, order.removeAt(from))
        save()
        render()
    }

    private fun save() {
        MessageAppsPrefs.setOrdered(this, order)
        MirrorNotificationListener.requestMessagesSync()
    }

    private fun text(value: String, sizeSp: Float, color: Int, top: Int = 0, bottom: Int = 0) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(getColor(color))
        setPadding(0, dp(top), 0, dp(bottom))
    }

    @Suppress("DEPRECATION")
    private fun loadApps(): List<AppRow> {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .asSequence()
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != packageName }
            .map { AppRow(it.packageName, pm.getApplicationLabel(it).toString(), pm.getApplicationIcon(it)) }
            .toList()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
