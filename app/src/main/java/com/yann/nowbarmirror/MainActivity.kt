package com.yann.nowbarmirror

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.yann.nowbarmirror.settings.AppSelectionActivity
import com.yann.nowbarmirror.settings.MessageAppsActivity
import com.yann.nowbarmirror.settings.PermissionsActivity
import com.yann.nowbarmirror.settings.SettingsActivity
import com.yann.nowbarmirror.sport.SportActivity

/**
 * Main screen (reworked 24/09/2026 — no more Accueil/Sport tabs): five entries, each opening its
 * own screen. The status line summarizes what's still missing in Autorisations.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // targetSdk 36 forces edge-to-edge: without this the title sits under the status bar.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        bind(R.id.app_selection_button) { AppSelectionActivity::class.java }
        bind(R.id.sport_button) { SportActivity::class.java }
        bind(R.id.message_apps_button) { MessageAppsActivity::class.java }
        bind(R.id.settings_button) { SettingsActivity::class.java }
        bind(R.id.permissions_button) { PermissionsActivity::class.java }

        status = findViewById(R.id.status_text)
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun bind(id: Int, target: () -> Class<*>) {
        findViewById<MaterialButton>(id).setOnClickListener { startActivity(Intent(this, target())) }
    }

    private fun refreshStatus() {
        val missing = PermissionsActivity.missingCount(this)
        status.text = if (missing == 0) {
            getString(R.string.permissions_all_ok)
        } else {
            getString(R.string.permissions_missing, missing)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
