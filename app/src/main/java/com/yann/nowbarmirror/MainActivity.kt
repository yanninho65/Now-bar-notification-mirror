package com.yann.nowbarmirror

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.yann.nowbarmirror.settings.AppSelectionActivity

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        val title = TextView(this).apply {
            text = "Now Bar Mirror"
            textSize = 28f
        }
        val info = TextView(this).apply {
            text = "Cette app reflète la dernière notification reçue dans une notification persistante compatible Now Bar.\n\nElle nécessite l’accès aux notifications."
            textSize = 16f
            setPadding(0, 24, 0, 24)
        }
        val access = Button(this).apply {
            text = "Autoriser l’accès aux notifications"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        val notif = Button(this).apply {
            text = "Autoriser les notifications de l’app"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 33) {
                    ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
                }
            }
        }
        val appSelection = Button(this).apply {
            text = "Applications à mirrorer"
            setOnClickListener { startActivity(Intent(this@MainActivity, AppSelectionActivity::class.java)) }
        }
        status = TextView(this).apply { textSize = 15f; setPadding(0, 24, 0, 0) }
        layout.addView(title)
        layout.addView(info)
        layout.addView(access)
        layout.addView(notif)
        layout.addView(appSelection)
        layout.addView(status)
        setContentView(layout)

        // targetSdk 36 forces edge-to-edge: content draws behind the status/nav bars by
        // default. Without this, the title (and on some screens a top control) ends up
        // hidden under the status bar. Add the system bar insets on top of the base padding.
        val basePadding = 48
        ViewCompat.setOnApplyWindowInsetsListener(layout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                basePadding + bars.left,
                basePadding + bars.top,
                basePadding + bars.right,
                basePadding + bars.bottom
            )
            insets
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        status.text = if (enabled) "✓ Accès aux notifications activé" else "⚠ Accès aux notifications non activé"
    }
}
