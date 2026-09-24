package com.yann.nowbarmirror

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.yann.nowbarmirror.sport.SofascoreNotificationListenerService

/**
 * FIX (17/09/2026, Yann: "J'ai l'impression que l'application ne lit pas vraiment toutes les
 * notifications dans le centre de notif pour avoir l'historique complet. Dans le widget vue
 * toutes notifs, je ne vois que celles apparues après la mise à jour.") for a gap the same-day
 * "catch up on already-active notifications" fix (see MirrorNotificationListener.
 * rebuildStateFromActiveNotifications / SofascoreNotificationListenerService.
 * bootstrapAllNotificationsHistory, and this project's README, section "Architecture") didn't
 * cover: that catch-up code only ever RUNS from onListenerConnected(), and Android does not
 * reliably call onListenerConnected() again just because the app's APK was updated in place —
 * unlike a fresh install or the user re-granting notification access, an in-place update often
 * leaves an existing NotificationListenerService binding alone (this is especially true on
 * Samsung/One UI), so the service process can keep running against its OLD code without ever
 * re-entering onListenerConnected() and therefore without ever running the catch-up — which is
 * exactly "je ne vois que celles apparues après la mise à jour": not a bug in the catch-up logic
 * itself, but the catch-up never firing after an update in the first place.
 *
 * The standard, documented workaround (used by most notification-listener apps for this exact
 * problem) is to force Android to unbind and rebind every NotificationListenerService component
 * right after an update, which is what makes onListenerConnected() fire again on demand: briefly
 * flipping a service component to DISABLED and back to ENABLED via PackageManager makes the
 * system treat it as a fresh listener and rebind it, re-running onListenerConnected() (and, with
 * it, the already-correct catch-up code) — without restarting the app or needing the user to do
 * anything (toggle notification access off/on, or reboot the phone) themselves.
 *
 * ACTION_MY_PACKAGE_REPLACED is a special implicit broadcast Android sends ONLY to the app that
 * was just updated, ONLY right after that update finishes — exactly the moment this needs to run
 * at, and unaffected by the background-broadcast restrictions that apply to most other implicit
 * broadcasts since Android 8, so a plain manifest-registered receiver for it (see
 * AndroidManifest.xml) is enough; no separate "keep this process alive to listen for it" service
 * is needed.
 */
class PackageUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        forceRebind(context, MirrorNotificationListener::class.java)
        forceRebind(context, SofascoreNotificationListenerService::class.java)

        // Leftovers of the sport-API overrides removed 24/09/2026 (incl. the stored Live Tennis API key).
        listOf("sofascore_api_overrides", "tennis_api_key").forEach {
            try {
                context.deleteSharedPreferences(it)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * DISABLED then ENABLED, both with DONT_KILL_APP so this never restarts the whole process —
     * only the notification-listener binding itself is churned. Wrapped in try/catch: this is a
     * best-effort nudge to make the existing catch-up code run promptly after an update, not a
     * feature on its own — worst case (some OEM quirk refuses the toggle) is the exact same
     * behavior as before this fix (catch-up waits for the next natural reconnect), never a crash.
     */
    private fun forceRebind(context: Context, serviceClass: Class<*>) {
        try {
            val packageManager = context.packageManager
            val component = ComponentName(context, serviceClass)
            packageManager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            packageManager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Throwable) {
            // Never let this nice-to-have take the update process down — see class doc.
        }
    }
}
