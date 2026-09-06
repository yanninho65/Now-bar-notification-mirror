package com.yann.nowbarmirror.settings

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CompoundButton
import android.widget.Switch
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yann.nowbarmirror.R

class AppSelectionActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView

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
                refresh()
                Toast.makeText(this, R.string.import_success, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_app_selection)

        // Same edge-to-edge fix as MainActivity: without this the "activer le service" row
        // (the very first view here) ends up drawn under the status bar.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        recyclerView = findViewById(R.id.app_list)
        recyclerView.layoutManager = LinearLayoutManager(this)

        findViewById<Switch>(R.id.service_enabled_switch).apply {
            isChecked = ServicePrefs.isEnabled(this@AppSelectionActivity)
            setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
                ServicePrefs.setEnabled(this@AppSelectionActivity, isChecked)
            }
        }

        findViewById<Button>(R.id.export_button).setOnClickListener {
            exportLauncher.launch("nowbarmirror-settings.json")
        }
        findViewById<Button>(R.id.import_button).setOnClickListener {
            importLauncher.launch(arrayOf("application/json"))
        }

        refresh()
    }

    private fun refresh() {
        recyclerView.adapter = AppSelectionAdapter(this, loadSelectableApps())
    }

    /** User-facing apps only (i.e. apps with a launcher icon), excluding this app itself. */
    private fun loadSelectableApps(): List<SelectableApp> {
        val pm = packageManager
        return installedApplications(pm)
            .asSequence()
            .filter { it.packageName != packageName }
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map {
                SelectableApp(
                    packageName = it.packageName,
                    label = pm.getApplicationLabel(it).toString(),
                    icon = pm.getApplicationIcon(it)
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    @Suppress("DEPRECATION")
    private fun installedApplications(pm: PackageManager): List<ApplicationInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            pm.getInstalledApplications(0)
        }
    }
}
