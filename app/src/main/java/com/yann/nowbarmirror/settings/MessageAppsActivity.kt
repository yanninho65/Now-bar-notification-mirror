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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.yann.nowbarmirror.MirrorNotificationListener
import com.yann.nowbarmirror.R

/**
 * NEW 24/09/2026 — picks the apps whose notifications feed the watch "Messages" complication
 * ([MessageAppsPrefs]). Simple code-built checkbox list of launcher apps (selected first, then
 * alphabetical); every change is saved at once and re-pushes the list to the watch.
 */
class MessageAppsActivity : AppCompatActivity() {

    private data class AppRow(val packageName: String, val label: String, val icon: Drawable)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
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

        content.addView(TextView(this).apply {
            text = getString(R.string.message_apps_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(getColor(R.color.one_ui_text_primary))
            setPadding(0, dp(8), 0, dp(4))
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.message_apps_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(R.color.one_ui_text_secondary))
            setPadding(0, 0, 0, dp(12))
        })

        val selected = MessageAppsPrefs.get(this).toMutableSet()
        loadApps(selected).forEach { app ->
            content.addView(CheckBox(this).apply {
                text = app.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(getColor(R.color.one_ui_text_primary))
                gravity = Gravity.CENTER_VERTICAL
                app.icon.setBounds(0, 0, dp(32), dp(32))
                setCompoundDrawablesRelative(null, null, app.icon, null)
                compoundDrawablePadding = dp(12)
                setPadding(dp(8), dp(10), dp(8), dp(10))
                isChecked = app.packageName in selected
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(app.packageName) else selected.remove(app.packageName)
                    MessageAppsPrefs.set(this@MessageAppsActivity, selected)
                    MirrorNotificationListener.requestMessagesSync()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    @Suppress("DEPRECATION")
    private fun loadApps(selected: Set<String>): List<AppRow> {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .asSequence()
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != packageName }
            .map { AppRow(it.packageName, pm.getApplicationLabel(it).toString(), pm.getApplicationIcon(it)) }
            .sortedWith(compareBy<AppRow>({ it.packageName !in selected }, { it.label.lowercase() }))
            .toList()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
