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
 * Invisible "trampoline" Activity (NEW 20/09/2026) for a tile's own tap — the one that OPENS a
 * "peek" (WidgetPeekPrefs' class doc), not PeekOpenTrampolineActivity's later tap on the peek's
 * own text, which relays to the real notification/app. Until now this tap was a plain broadcast
 * PendingIntent to NowBarWidgetProvider's own onReceive (ACTION_OPEN_PEEK, removed — see
 * openPeekPendingIntent's doc). That is structurally the exact same "second-hop, not a direct
 * lock-widget Activity tap" situation PeekOpenTrampolineActivity's class doc already worked
 * through for the peek's open-notification tap, so it inherited the same "requires a manual swipe
 * to leave the lock screen" limitation. Yann, after the first trampoline fix: "en vue icônes
 * toutes notif, ça deverrouille juste le lockscreen (ça ouvre l'appli quand je clique quand on
 * n'est plus sur lockscreen mais pas l'article/message) [...] je voudrais que ça soit pareil que
 * dernière notif pour limiter les touches" — the same "minimize touches" goal applies just as much
 * to the tile's own tap as to the peek's later text tap.
 *
 * Same fix, same reasoning as PeekOpenTrampolineActivity: make the tile's tap a single-hop, direct
 * Activity PendingIntent (this activity, not a broadcast), so the widget host treats it exactly
 * like Dernière notif's own direct tap and dismisses a non-secure keyguard without an extra manual
 * swipe. There's no second PendingIntent to relay to here — opening a peek is a self-contained,
 * in-widget action (it just switches what the SAME widget_latest_content block shows) — so
 * onCreate only needs to record the peek and rebuild the widget before finishing immediately;
 * never actually visible, same transparent/no-animation theme as PeekOpenTrampolineActivity.
 */
class OpenPeekTrampolineActivity : Activity() {

    companion object {
        private const val EXTRA_SOURCE = "mirror.widget.open_peek_trampoline_source"
        private const val EXTRA_ENTRY_ID = "mirror.widget.open_peek_trampoline_entry_id"

        /** Builds the single Activity PendingIntent a tile's "open peek" tap should be bound to — see the class doc for why this must be an Activity, not a broadcast. [requestCode] must stay distinct per rendered tile, same as the broadcast this replaces did (PEEK_REQUEST_CODE_SPORT_BASE/PEEK_REQUEST_CODE_ALL_NOTIFS_BASE in NowBarWidgetProvider). */
        fun pendingIntent(context: Context, source: WidgetPeekPrefs.Source, entryId: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, OpenPeekTrampolineActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(EXTRA_SOURCE, source.name)
                putExtra(EXTRA_ENTRY_ID, entryId)
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

        // Show over / dismiss a (non-secure) keyguard directly, same as PeekOpenTrampolineActivity
        // and as Dernière notif's own tap already benefits from as a lock-screen widget's direct
        // Activity PendingIntent. Harmless no-op if there's no active keyguard, or if the device's
        // lock is secure (PIN/pattern/biometric) — in that case this just triggers the same
        // authentication challenge tapping any other lock-screen action would.
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

        val sourceName = intent.getStringExtra(EXTRA_SOURCE)
        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID)
        val source = sourceName?.let { name ->
            try {
                WidgetPeekPrefs.Source.valueOf(name)
            } catch (_: Throwable) {
                null
            }
        }
        if (source != null && entryId != null) {
            WidgetPeekPrefs.open(this, source, entryId)
            NowBarWidgetProvider.requestUpdate(this)
        }

        finish()
        overridePendingTransition(0, 0)
    }
}
