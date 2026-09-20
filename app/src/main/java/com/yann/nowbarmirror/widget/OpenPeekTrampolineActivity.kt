package com.yann.nowbarmirror.widget

import android.app.Activity
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
 *
 * FIXED 20/09/2026 (same day as this activity's own introduction) — Yann, testing on the lock
 * screen: "quand je clique sur l'icône dans vue toutes notifs, ça me deverrouille le téléphone
 * [au lieu d'juste] ouvrir la vue texte de cette notif [en restant sur le lockscreen]". Root
 * cause: onCreate copy-pasted PeekOpenTrampolineActivity's `requestDismissKeyguard()` call
 * wholesale, but the two activities want OPPOSITE outcomes on a secure (PIN/pattern/biometric)
 * lock screen. PeekOpenTrampolineActivity's whole job is to actually leave the lock screen and
 * land in the real target app/notification, so fully dismissing the keyguard there is correct.
 * This activity's job is the opposite — flip the SAME widget in place from its tile grid to the
 * peek's full-format content while staying ON the lock screen, exactly like tapping the left
 * toggle or the dismiss button already does without ever unlocking anything. On a secure device,
 * `requestDismissKeyguard()` doesn't just let this transparent activity draw over the keyguard
 * for a moment — once the user clears the credential challenge it raises, the keyguard is gone
 * for good, i.e. the phone ends up fully unlocked, which is exactly the "ça me deverrouille le
 * téléphone" Yann reported. `setShowWhenLocked`/`setTurnScreenOn` alone (kept below) are enough
 * to let this invisible activity run over a locked screen and trigger the widget rebuild — they
 * let it draw ON TOP of the keyguard without asking the keyguard to go away, so the lock screen
 * (now showing the peek) is still exactly what's showing once this activity finishes.
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

        // Show OVER a locked screen WITHOUT dismissing it (FIXED 20/09/2026 — see class doc for
        // the "ça me deverrouille le téléphone" bug this caused): unlike PeekOpenTrampolineActivity,
        // this activity must NOT call KeyguardManager.requestDismissKeyguard(), since its job is to
        // update the widget in place and leave the user exactly where they were — on the lock
        // screen, now showing the peek — not to actually unlock the device. setShowWhenLocked (+
        // setTurnScreenOn, in case the screen was off) is sufficient for this invisible activity to
        // run and trigger the widget rebuild while a secure keyguard stays fully in place.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

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
