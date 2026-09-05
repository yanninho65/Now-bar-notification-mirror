package com.yann.nowbarmirror

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        status = TextView(this).apply { textSize = 15f; setPadding(0, 24, 0, 0) }
        layout.addView(title)
        layout.addView(info)
        layout.addView(access)
        layout.addView(notif)
        layout.addView(status)
        setContentView(layout)
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
