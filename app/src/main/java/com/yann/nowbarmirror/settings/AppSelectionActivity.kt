package com.yann.nowbarmirror.settings

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yann.nowbarmirror.R

/** "Applications à mirrorer": full-screen app list (options and export/import moved to SettingsActivity, 24/09/2026). */
class AppSelectionActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_app_selection)

        // Same edge-to-edge fix as MainActivity: without this the title ends up under the status bar.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        recyclerView = findViewById(R.id.app_list)
        recyclerView.layoutManager = LinearLayoutManager(this)
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
