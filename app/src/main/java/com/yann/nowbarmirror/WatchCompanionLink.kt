package com.yann.nowbarmirror

import android.app.ActivityOptions
import android.app.PendingIntent
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * NEW 24/09/2026 — "Associer la montre" (Autorisations). Yann had to re-grant the full-screen-intent
 * access after every update: that access is an app-op the installer can reset on each install
 * session, so it can't be relied on. A CompanionDeviceManager association with the watch is kept
 * across updates, and Android lets an associated companion app start an activity from the
 * background in response to an action on the paired device — exactly "Aff. sur tél.".
 *
 * [openDirect] is what NowBarWidgetProvider.postOpenOnPhone tries first; the relay notification
 * (+ full-screen intent when still granted) stays as the fallback.
 */
object WatchCompanionLink {

    fun isAssociated(context: Context): Boolean = try {
        val cdm = context.getSystemService(CompanionDeviceManager::class.java)
        when {
            cdm == null -> false
            Build.VERSION.SDK_INT >= 33 -> cdm.myAssociations.isNotEmpty()
            else -> @Suppress("DEPRECATION") cdm.associations.isNotEmpty()
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * Starts the system "choose a device" flow. [onPending] receives the IntentSender to launch
     * (the activity starts it with StartIntentSenderForResult); [onDone] is called on success
     * (API 33+ reports it directly, older versions through the activity result).
     */
    fun associate(
        context: Context,
        onPending: (IntentSender) -> Unit,
        onDone: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val cdm = context.getSystemService(CompanionDeviceManager::class.java)
        if (cdm == null) {
            onFailure("Association non disponible sur ce téléphone")
            return
        }
        // No name filter: the watch's Bluetooth name varies ("Galaxy Watch8 (xxxx)"), Yann picks it.
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().build())
            .setSingleDevice(false)
            .build()
        val callback = object : CompanionDeviceManager.Callback() {
            @Deprecated("API < 33")
            override fun onDeviceFound(intentSender: IntentSender) = onPending(intentSender)
            override fun onAssociationPending(intentSender: IntentSender) = onPending(intentSender)
            override fun onAssociationCreated(associationInfo: AssociationInfo) = onDone()
            override fun onFailure(error: CharSequence?) = onFailure(error?.toString() ?: "Échec de l'association")
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                cdm.associate(request, context.mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                cdm.associate(request, callback, Handler(Looper.getMainLooper()))
            }
        } catch (t: Throwable) {
            onFailure(t.message ?: "Échec de l'association")
        }
    }

    /**
     * Sends [openIntent] straight away with this app's own background-start privilege (companion
     * association). Returns false — caller falls back to the relay notification — when not
     * associated or when the system refuses the launch.
     */
    fun openDirect(context: Context, openIntent: PendingIntent): Boolean {
        if (!isAssociated(context)) return false
        return try {
            val options = when {
                Build.VERSION.SDK_INT >= 36 -> ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
                    .toBundle()
                Build.VERSION.SDK_INT >= 34 -> ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        @Suppress("DEPRECATION") ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                    .toBundle()
                else -> null
            }
            openIntent.send(context, 0, null, null, null, null, options)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
