package com.yann.nowbarmirror.settings

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.yann.nowbarmirror.MirrorNotificationListener
import com.yann.nowbarmirror.R
import com.yann.nowbarmirror.WatchCompanionLink
import com.yann.nowbarmirror.sport.SofascoreNotificationListenerService

/**
 * "Autorisations" (24/09/2026): everything the app needs from system settings, grouped in one
 * screen with a status line per entry — notification access (both listeners: mirroring and Sport
 * share the same system screen), the app's own notifications, the watch association (see
 * WatchCompanionLink — what makes "Aff. sur tél." survive updates) and, Android 14+, the
 * full-screen-intent access (only needed when the watch isn't associated).
 */
class PermissionsActivity : AppCompatActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private val associationLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_permissions)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        findViewById<MaterialButton>(R.id.access_button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<MaterialButton>(R.id.notif_button).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !postNotificationsGranted(this)) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openAppNotificationSettings()
            }
        }
        findViewById<MaterialButton>(R.id.watch_link_button).setOnClickListener { startAssociation() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            listOf(R.id.full_screen_intent_divider, R.id.full_screen_intent_button, R.id.full_screen_intent_status)
                .forEach { findViewById<View>(it).visibility = View.VISIBLE }
            findViewById<MaterialButton>(R.id.full_screen_intent_button).setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val mirror = listenerGranted(this, MirrorNotificationListener::class.java)
        val sport = listenerGranted(this, SofascoreNotificationListenerService::class.java)
        findViewById<TextView>(R.id.access_status).text =
            "${mark(mirror)} ${getString(R.string.access_mirror)}   ${mark(sport)} ${getString(R.string.access_sport)}"

        findViewById<TextView>(R.id.notif_status).text =
            if (postNotificationsGranted(this)) getString(R.string.status_granted) else getString(R.string.status_not_granted)

        findViewById<TextView>(R.id.watch_link_status).text =
            if (WatchCompanionLink.isAssociated(this)) getString(R.string.watch_link_ok) else getString(R.string.watch_link_missing)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            findViewById<TextView>(R.id.full_screen_intent_status).text =
                if (fullScreenGranted(this)) getString(R.string.full_screen_intent_enabled)
                else getString(R.string.full_screen_intent_disabled)
        }
    }

    private fun startAssociation() {
        WatchCompanionLink.associate(
            this,
            onPending = { sender -> associationLauncher.launch(IntentSenderRequest.Builder(sender).build()) },
            onDone = { runOnUiThread { refresh() } },
            onFailure = { message -> runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() } }
        )
    }

    private fun openAppNotificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    private fun mark(ok: Boolean) = if (ok) "✓" else "⚠"

    companion object {

        fun listenerGranted(context: Context, service: Class<*>): Boolean =
            if (Build.VERSION.SDK_INT >= 27) {
                context.getSystemService(NotificationManager::class.java)
                    .isNotificationListenerAccessGranted(ComponentName(context, service))
            } else {
                NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
            }

        fun postNotificationsGranted(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

        fun fullScreenGranted(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

        /** Entries still to grant; "Aff. sur tél." counts once (watch association OR full-screen access). */
        fun missingCount(context: Context): Int = listOf(
            listenerGranted(context, MirrorNotificationListener::class.java),
            listenerGranted(context, SofascoreNotificationListenerService::class.java),
            postNotificationsGranted(context),
            WatchCompanionLink.isAssociated(context) || fullScreenGranted(context)
        ).count { !it }
    }
}
