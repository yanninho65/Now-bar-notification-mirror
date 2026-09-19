package com.yann.nowbarmirror.widget

import android.app.Activity
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

/**
 * Invisible "trampoline" Activity (NEW 18/09/2026) fixing a regression in the peek-close feature
 * above (WidgetPeekPrefs' class doc, NowBarWidgetProvider.resolvePeek). Tapping a peek's text is
 * meant to do two things at once: close the peek (back to the tile grid) AND open the real
 * notification/app — Yann: "quand je clique sur une icône puis sur le texte pour ouvrir la notif,
 * revenir aux icônes dans le widget [...] il faut quand même que ça ouvre la notification".
 *
 * The first attempt at this routed the tap through NowBarWidgetProvider's own BroadcastReceiver
 * (close the peek, rebuild the widget, THEN call the real target PendingIntent's .send()) — which
 * turned out to break in two stages once actually tested on-device:
 *
 * 1. A plain `target.send()` from inside a BroadcastReceiver's onReceive silently did nothing
 *    beyond closing the peek (Yann: "je t'assure que ça n'ouvre pas la notif. Ça revient juste aux
 *    icônes.") — Android's background-activity-start restrictions vetoed it with no exception.
 * 2. Forcing that send through anyway (`ActivityOptions.setPendingIntentBackgroundActivityStartMode`)
 *    got the notification to actually open, but only behind a manual "swipe up" to leave the lock
 *    screen, unlike Dernière notif's tap-to-open, which opens directly with no swipe needed (Yann:
 *    "je dois glisser mon doigt sortir du lockscreen [...] je voudrais que ce soit pareil que
 *    dernière notif pour limiter les touches"). Dernière notif's tap is bound DIRECTLY to the real
 *    target PendingIntent — ONE hop, fired by the lock-widget host (LockStar) itself as the
 *    immediate result of the user's tap, which is what lets it dismiss a (non-secure) keyguard
 *    with no extra gesture. Routing through this app's own BroadcastReceiver first added a SECOND
 *    hop that doesn't carry the same "this is a direct, privileged, lock-screen-widget tap"
 *    treatment, even once the activity itself is allowed to start.
 *
 * The fix: make the SINGLE hop from the widget host be an Activity PendingIntent again (this
 * activity, not a broadcast) — from the widget host's point of view this is structurally
 * IDENTICAL to Dernière notif's own direct tap (a lock-screen widget firing an Activity
 * PendingIntent), so it gets the exact same treatment. From here, now genuinely running as an
 * activity (never actually visible — see the transparent/no-animation theme and immediate
 * finish() below), it's completely ordinary for onCreate to close the peek, rebuild the widget,
 * and then relay to the REAL target via a second, perfectly normal PendingIntent.send() (starting
 * an activity from another foreground activity has never been subject to the background-start
 * restrictions above) — and to explicitly request dismissing the keyguard itself, in case the
 * device's own lock screen (rather than LockStar's overlay) is what's actually in the way.
 */
class PeekOpenTrampolineActivity : Activity() {

    companion object {
        private const val EXTRA_TARGET = "mirror.widget.peek_trampoline_target"

        /** Builds the single Activity PendingIntent a peek's tap-to-open should be bound to — see the class doc for why this must be an Activity, not a broadcast. [target] is the real notification/app PendingIntent to relay to once this activity actually runs. */
        fun pendingIntent(context: Context, requestCode: Int, target: PendingIntent): PendingIntent {
            val intent = Intent(context, PeekOpenTrampolineActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(EXTRA_TARGET, target)
            }
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over / dismiss a (non-secure) keyguard directly, same as Dernière notif's own tap
        // already benefits from as a lock-screen widget's direct Activity PendingIntent. Harmless
        // no-op if there's no active keyguard, or if the device's lock is secure (PIN/pattern/
        // biometric) — in that case this just triggers the same authentication challenge tapping
        // any other lock-screen action would.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)

        WidgetPeekPrefs.close(this)
        NowBarWidgetProvider.requestUpdate(this)

        targetFromIntent(intent)?.let { target ->
            try {
                target.send()
            } catch (_: PendingIntent.CanceledException) {
                // The wrapped notification/app PendingIntent is no longer valid (source
                // notification gone, process restarted) — the peek is already closed above, so
                // there's nothing further to do.
            }
        }

        finish()
        overridePendingTransition(0, 0)
    }

    @Suppress("DEPRECATION")
    private fun targetFromIntent(intent: Intent): PendingIntent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_TARGET, PendingIntent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_TARGET)
        }
    }
}
