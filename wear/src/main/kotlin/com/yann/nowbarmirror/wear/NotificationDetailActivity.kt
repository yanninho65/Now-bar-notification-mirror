package com.yann.nowbarmirror.wear

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.wear.widget.SwipeDismissFrameLayout
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataItemBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit

/**
 * NEW 21/09/2026 — écran plein écran ouvert par un tap sur la complication "Notification" (Yann :
 * "quand je clique sur la complication notification ça ouvre une fenêtre avec en haut l'image et
 * logo de la notif, la notification en grand pour lire le texte en entier et les boutons d'actions
 * de la notification et la possibilité de supprimer la notif. Adopter le style galaxy watch. Pour
 * les notifications comme les messages ou Sofascore, afficher toutes les notifs de l'expéditeur ou
 * toutes celles du match"). Voir NotificationComplicationService.notificationDetailTapAction pour
 * comment on arrive ici, et NotificationInfo/WatchNotificationSync.send pour d'où vient tout ce
 * que cet écran affiche.
 *
 * Style Galaxy Watch : fond noir OLED (activity_notification_detail.xml), gros
 * logo/image en haut, texte centré, boutons d'action en pilule et bouton de suppression rond en
 * bas, et le geste de balayage standard Wear OS pour fermer l'écran ([setupSwipeToDismiss]) plutôt
 * qu'un bouton "retour" dédié.
 *
 * `android.app.Activity` plutôt que ComponentActivity/AppCompatActivity : cet écran n'a besoin
 * d'aucune Fragment ni ActionBar, et ni androidx.activity ni androidx.appcompat ne sont déjà des
 * dépendances de ce module (voir wear/build.gradle.kts) — pas la peine d'en ajouter une pour ça.
 */
class NotificationDetailActivity : Activity() {

    private lateinit var appIconView: ImageView
    private lateinit var appNameView: TextView
    private lateinit var imageView: ImageView
    private lateinit var titleView: TextView
    private lateinit var textView: TextView
    private lateinit var linesContainer: LinearLayout
    private lateinit var actionsContainer: LinearLayout
    private lateinit var deleteButton: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_detail)

        setupSwipeToDismiss()

        appIconView = findViewById(R.id.detail_app_icon)
        appNameView = findViewById(R.id.detail_app_name)
        imageView = findViewById(R.id.detail_image)
        titleView = findViewById(R.id.detail_title)
        textView = findViewById(R.id.detail_text)
        linesContainer = findViewById(R.id.detail_lines_container)
        actionsContainer = findViewById(R.id.detail_actions_container)
        deleteButton = findViewById(R.id.detail_delete_button)

        // FIXED 21/09/2026 (Yann : "ça ne montre pas toujours la notification qui est affichée sur
        // la complication [...] il faut vraiment que ça soit toujours la même [...] ça reste
        // bloqué sur une ancienne et sans avoir l'exhaustivité") : faire confiance à
        // NotificationInfoStore.current sans jamais le revérifier laissait cette fenêtre bloquée
        // sur une valeur périmée si CE process avait raté la dernière mise à jour en direct (même
        // si la complication, elle, avait par ailleurs reçu la sienne) — voir
        // NotificationComplicationService's doc, qui applique désormais la même correction.
        // Peint immédiatement ce qui est déjà en cache (pour éviter un écran vide le temps de la
        // relecture ci-dessous, souvent déjà correct) puis écrase avec le résultat de la relecture
        // de l'item persistant — un appel purement local, quelques dizaines de ms — dès qu'il
        // arrive, sauf si cette relecture échoue elle-même (repli sur ce qui est déjà affiché).
        NotificationInfoStore.current?.let { render(it) } ?: renderEmpty()

        Thread {
            val outcome = fetchPersistedNotification()
            runOnUiThread {
                when (outcome) {
                    is FetchOutcome.Success -> if (outcome.info != null) render(outcome.info) else renderEmpty()
                    FetchOutcome.Failed -> Unit
                }
            }
        }.start()
    }

    private fun setupSwipeToDismiss() {
        val swipeLayout = findViewById<SwipeDismissFrameLayout>(R.id.swipe_layout)
        swipeLayout.addCallback(object : SwipeDismissFrameLayout.Callback() {
            override fun onDismissed(layout: SwipeDismissFrameLayout) {
                layout.visibility = View.GONE
                finish()
            }
        })
    }

    private fun renderEmpty() {
        titleView.text = "Aucune notification"
        textView.visibility = View.GONE
        imageView.visibility = View.GONE
        appIconView.visibility = View.GONE
        appNameView.visibility = View.GONE
        linesContainer.visibility = View.GONE
        actionsContainer.visibility = View.GONE
        deleteButton.visibility = View.GONE
    }

    private fun render(info: NotificationInfo) {
        currentInfoForActions = info
        titleView.text = info.title.ifBlank { "Notification" }

        // Logo (petite icône de l'app source, en-tête) — texte associé seulement pour Sofascore
        // (seul cas où on connaît un nom d'app fiable sans dépendre d'un PackageManager côté
        // montre, qui n'a pas forcément l'app source installée — voir la doc de classe).
        if (info.appIcon != null) {
            appIconView.setImageBitmap(circularBitmap(info.appIcon))
            appIconView.visibility = View.VISIBLE
        } else {
            appIconView.visibility = View.GONE
        }
        if (info.kind == "SOFASCORE_MATCH") {
            appNameView.text = "Sofascore"
            appNameView.visibility = View.VISIBLE
        } else {
            appNameView.visibility = View.GONE
        }

        // Image en grand — l'image de la notif elle-même, ou à défaut le logo de l'app en plus
        // grand (même repli que ComplicationImageComposer côté "petit rond" de la complication).
        val bigImage = info.image ?: info.appIcon
        if (bigImage != null) {
            imageView.setImageBitmap(circularBitmap(bigImage))
            imageView.visibility = View.VISIBLE
        } else {
            imageView.visibility = View.GONE
        }

        // Corps : l'historique (messages de la conversation / lignes d'événements du match) s'il y
        // en a un, sinon le texte simple de la notification — voir NotificationInfo.detailLines'
        // doc pour pourquoi les deux ne sont jamais affichés ensemble (la ligne la plus récente de
        // l'historique EST déjà ce texte).
        if (info.detailLines.isNotEmpty()) {
            textView.visibility = View.GONE
            linesContainer.visibility = View.VISIBLE
            linesContainer.removeAllViews()
            info.detailLines.forEach { line -> linesContainer.addView(lineCard(line)) }
        } else if (info.text.isNotBlank()) {
            linesContainer.visibility = View.GONE
            textView.visibility = View.VISIBLE
            textView.text = info.text
        } else {
            textView.visibility = View.GONE
            linesContainer.visibility = View.GONE
        }

        // Boutons d'action — seuls les LIBELLÉS ont pu traverser jusqu'à la montre (voir
        // NotificationInfo's doc) ; le tap envoie juste "actionIndex=N" au téléphone, qui retrouve
        // le vrai PendingIntent dans son propre cache (NowBarWidgetProvider.fireAction).
        actionsContainer.removeAllViews()
        if (info.actionLabels.isNotEmpty()) {
            actionsContainer.visibility = View.VISIBLE
            info.actionLabels.forEachIndexed { index, label ->
                actionsContainer.addView(actionChip(label, index))
            }
        } else {
            actionsContainer.visibility = View.GONE
        }

        deleteButton.visibility = View.VISIBLE
        deleteButton.setOnClickListener {
            PhoneRelay.sendDismiss(applicationContext, info)
            // Retour immédiat, sans attendre la confirmation asynchrone du téléphone — même esprit
            // que le reste de cette app (voir PhoneRelay's doc).
            finish()
        }
    }

    private fun lineCard(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(resources.getColor(R.color.detail_text_primary, theme))
            textSize = 13f
            setBackgroundResource(R.drawable.bg_line_card)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = dp(8)
            layoutParams = params
        }
    }

    private fun actionChip(label: String, index: Int): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(resources.getColor(R.color.detail_text_primary, theme))
            textSize = 12f
            setBackgroundResource(R.drawable.bg_action_chip)
            setPadding(dp(20), dp(8), dp(20), dp(8))
            minWidth = 0
            minimumWidth = 0
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginStart = dp(6)
            params.marginEnd = dp(6)
            layoutParams = params
            setOnClickListener { PhoneRelay.sendAction(applicationContext, currentInfoForActions, index) }
        }
    }

    /** dp -> px, pour les LayoutParams/paddings construits en code (les layouts XML gèrent ça tout seuls). */
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // Le chip a besoin de la même NotificationInfo que celle actuellement rendue pour adresser sa
    // demande — voir PhoneRelay.sendAction. Renseigné juste avant de construire les chips.
    private lateinit var currentInfoForActions: NotificationInfo

    /** Recadre [source] en cercle — même algorithme que mobile/widget/NowBarWidgetProvider.circularBitmap (dupliqué, modules Gradle distincts). */
    private fun circularBitmap(source: Bitmap): Bitmap {
        val size = minOf(source.width, source.height).coerceAtLeast(1)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawOval(RectF(0f, 0f, size.toFloat(), size.toFloat()), paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val left = (source.width - size) / 2f
        val top = (source.height - size) / 2f
        canvas.drawBitmap(source, -left, -top, paint)
        return output
    }

    /** Résultat d'une relecture de l'item persistant "/notification" — voir [fetchPersistedNotification] et NotificationComplicationService's identique. */
    private sealed class FetchOutcome {
        data class Success(val info: NotificationInfo?) : FetchOutcome()
        object Failed : FetchOutcome()
    }

    /** Relit toujours l'item persistant plutôt que de faire confiance au cache mémoire — voir la doc d'onCreate et NotificationComplicationService.fetchPersistedNotification (même logique, dupliquée ici : composants Wear distincts). */
    private fun fetchPersistedNotification(): FetchOutcome {
        return try {
            val items: DataItemBuffer = Tasks.await(
                Wearable.getDataClient(this).getDataItems(),
                FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS
            )
            try {
                val item = items.firstOrNull { it.uri.path == NOTIFICATION_PATH }
                val info = item?.let { NotificationDataCodec.decode(this, DataMapItem.fromDataItem(it).dataMap) }
                NotificationInfoStore.current = info
                FetchOutcome.Success(info)
            } finally {
                items.release()
            }
        } catch (e: Exception) {
            FetchOutcome.Failed
        }
    }

    companion object {
        private const val NOTIFICATION_PATH = "/notification"
        private const val FETCH_TIMEOUT_SECONDS = 2L
    }
}
