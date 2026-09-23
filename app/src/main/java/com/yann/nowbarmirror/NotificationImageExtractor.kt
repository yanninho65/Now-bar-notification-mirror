package com.yann.nowbarmirror

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/**
 * Extraction d'image partagée entre [MirrorNotificationListener] (miroir
 * générique, toutes apps) et [com.yann.nowbarmirror.sport.SofascoreNotificationListenerService]
 * (traitement à part de Sofascore) — fusionnée lors du rapprochement avec
 * Sport Watch Complication (voir README.md, section "Fusion avec Sport
 * Watch Complication") : "en fusionnant ce qui peut l'être comme l'écoute
 * des notifications et la récupération des images" (demandé par Yann).
 *
 * Ordre des pistes essayées — VOLONTAIREMENT celui déjà utilisé par
 * MirrorNotificationListener avant la fusion (photo de contact
 * MessagingStyle, puis EXTRA_PICTURE, puis getLargeIcon()), pour ne rien
 * changer au comportement déjà en place pour le mirroring générique.
 * [EXTRA_LARGE_ICON] est ajouté en dernier recours : c'était une piste de
 * SofascoreNotificationListenerService (jamais confirmée nécessaire sur
 * appareil, voir son historique), gardée par prudence plutôt que perdue en
 * fusionnant, mais placée après les trois pistes historiques pour ne rien
 * changer à leur ordre de priorité :
 * 1. photo de contact d'un message MessagingStyle (WhatsApp, Messages...
 *    la posent ici, PAS dans le large icon)
 * 2. EXTRA_PICTURE (BigPictureStyle — "grand visuel" d'une notif enrichie,
 *    ex. l'image combinée que Sofascore compose pour sa propre notif)
 * 3. getLargeIcon() (API `Icon`, seule voie fiable depuis Android 23 —
 *    reflète tout ce qui a été posé via `setLargeIcon`, quel que soit le
 *    Builder utilisé en interne par l'app source)
 * 4. EXTRA_LARGE_ICON (extra Bitmap historique — au cas où l'image ne
 *    serait exposée par aucune des pistes ci-dessus, ex. Builder
 *    personnalisé/RemoteViews)
 *
 * `null` si aucune des quatre n'aboutit.
 */
object NotificationImageExtractor {

    fun extract(context: Context, sbn: StatusBarNotification): Bitmap? {
        val n = sbn.notification

        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
            ?.messages
            ?.lastOrNull { it.person?.icon != null }
            ?.person?.icon
            ?.let { personIcon ->
                drawableFromIcon(context, personIcon.toIcon(context))?.let { return it }
            }

        val bigPicture: Bitmap? = if (Build.VERSION.SDK_INT >= 33) {
            n.extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
        } else {
            @Suppress("DEPRECATION") n.extras.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap
        }
        if (bigPicture != null) return bigPicture

        n.getLargeIcon()?.let { icon ->
            drawableFromIcon(context, icon)?.let { return it }
        }

        @Suppress("DEPRECATION")
        (n.extras.getParcelable(Notification.EXTRA_LARGE_ICON) as? Bitmap)?.let { return it }

        return null
    }

    private fun drawableFromIcon(context: Context, icon: Icon): Bitmap? {
        val drawable = try { icon.loadDrawable(context) } catch (_: Throwable) { null } ?: return null
        return BitmapUtils.drawableToBitmap(drawable)
    }
}
