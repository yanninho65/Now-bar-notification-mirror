package com.yann.nowbarmirror.settings

import android.os.Bundle
import android.widget.Button
import android.widget.CompoundButton
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.yann.nowbarmirror.MirrorNotificationListener
import com.yann.nowbarmirror.R

/** "Paramètres" (24/09/2026): the three switches and export/import, formerly at the top of the app list. */
class SettingsActivity : AppCompatActivity() {

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(SettingsBackup.export(this).toByteArray())
                }
                Toast.makeText(this, R.string.export_success, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                val json = contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                if (json == null) {
                    Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show()
                    return@registerForActivityResult
                }
                SettingsBackup.import(this, json)
                bindSwitches()
                MirrorNotificationListener.requestMessagesSync()
                Toast.makeText(this, R.string.import_success, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        bindSwitches()

        findViewById<Button>(R.id.export_button).setOnClickListener {
            exportLauncher.launch("nowbarmirror-settings.json")
        }
        findViewById<Button>(R.id.import_button).setOnClickListener {
            importLauncher.launch(arrayOf("application/json"))
        }
    }

    private fun bindSwitches() {
        bindSwitch(R.id.service_enabled_switch, ServicePrefs::isEnabled, ServicePrefs::setEnabled)
        bindSwitch(R.id.latest_mode_fallback_switch, LatestModePrefs::isFallbackEnabled, LatestModePrefs::setFallbackEnabled)
        bindSwitch(R.id.widget_actions_switch, WidgetActionsPrefs::isEnabled, WidgetActionsPrefs::setEnabled)
    }

    private fun bindSwitch(
        id: Int,
        get: (android.content.Context) -> Boolean,
        set: (android.content.Context, Boolean) -> Unit
    ) {
        findViewById<SwitchMaterial>(id).apply {
            setOnCheckedChangeListener(null)
            isChecked = get(this@SettingsActivity)
            setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                set(this@SettingsActivity, checked)
                MirrorNotificationListener.requestMessagesSync()   // "Service actif" empties/refills the watch list
            }
        }
    }
}
