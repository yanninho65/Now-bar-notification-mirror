package com.yann.nowbarmirror

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
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
        val status = TextView(this).apply { textSize = 15f; setPadding(0, 24, 0, 0) }
        layout.addView(title)
        layout.addView(info)
        layout.addView(access)
        layout.addView(notif)
        layout.addView(status)
        setContentView(layout)

        fun refresh() {
            val enabled = NotificationManager.getEnabledListenerPackages(this).contains(packageName)
            status.text = if (enabled) "✓ Accès aux notifications activé" else "⚠ Accès aux notifications non activé"
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // Listener state is displayed again when returning from Settings.
        val tv = (findViewById<LinearLayout>(android.R.id.content)?.getChildAt(0) as? LinearLayout)
            ?.getChildAt(4) as? TextView
        tv?.text = if (NotificationManager.getEnabledListenerPackages(this).contains(packageName))
            "✓ Accès aux notifications activé" else "⚠ Accès aux notifications non activé"
    }
}
