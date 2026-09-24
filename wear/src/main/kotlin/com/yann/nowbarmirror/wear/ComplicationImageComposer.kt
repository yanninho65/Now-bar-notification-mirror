package com.yann.nowbarmirror.wear

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

/**
 * Compose les images utilisées par les complications de score.
 *
 * - [composeRoundImage] : image UNIQUE (logos/notifImage + score + période,
 *   cuits dans le même bitmap, fond circulaire plein), pour le SMALL_IMAGE
 *   du rond Dashboard (voir ScoreComplicationService.buildSmallImage et le
 *   README, section "SMALL_IMAGE : disposition verticale (image en haut,
 *   score au milieu, période en bas)").
 *
 * Disposition REVUE le 16/09/2026 (retour à trois lignes empilées
 * verticalement, capture d'écran fournie par Yann à l'appui) : le
 * correctif du 15/09/2026 ("tout sur une ligne", voir historique README)
 * avait résolu le problème d'affichage mais produisait un rendu jugé trop
 * plat par Yann — l'image de notif, étirée dans un cadre 190×100 SANS
 * conserver son ratio d'origine, apparaissait aplatie, et le score,
 * minuscule à côté d'elle, était peu lisible. Cette version :
 * 1. image de notif (ou logos) EN HAUT, réduite, ratio d'origine conservé
 *    ([drawFittedBitmap] — fini l'étirement qui aplatissait l'image) ;
 * 2. score AU MILIEU, très agrandi (voir `SCORE_MAX_TEXT_SIZE` — encore
 *    revu à la hausse le 16/09/2026 suite au retour de Yann après cette
 *    première capture) ;
 * 3. période/statut (`MatchClock.label`) EN BAS, plus petit que le score,
 *    réduit automatiquement si le texte est trop long pour la corde du
 *    cercle à cette hauteur ([drawFittedText]) — absent de la version du
 *    15/09/2026, qui n'avait pas de place pour une 3e ligne dans son
 *    agencement "tout sur une ligne".
 * Les trois lignes restent chacune sous le rayon du cercle (vérifié au
 * pire cas, coin des rectangles compris) pour ne rien perdre au
 * recadrage circulaire du rond Dashboard.
 *
 * Les anciens composeurs d'icône carrée pour SHORT_TEXT (composeColorIcon,
 * composeMonochromeIcon) ont été RETIRÉS le 16/09/2026 en même temps que
 * SHORT_TEXT lui-même (voir README) — SMALL_IMAGE couvrait déjà tous les
 * cas d'usage réels.
 *
 * REPLI DEUX-LOGOS RETIRÉ le 16/09/2026 (même date, refonte "Sofascore
 * comme base" — voir README) : `drawColorLogo`/`MatchScore.homeLogo`/
 * `awayLogo` dessinaient les deux logos d'équipe API côte à côte quand
 * [MatchScore.notifImage] était `null`. Depuis cette date, Sofascore est
 * TOUJOURS la source de l'image (l'API ne fournit plus jamais de logo,
 * voir mobile/WatchSync.kt) : ce repli ne pouvait plus jamais afficher de
 * vrais logos, seulement ses deux pastilles grises placeholder — remplacé
 * par [drawPlaceholder], une SEULE pastille, plus simple pour le même
 * résultat visuel (montrer qu'il n'y a pas d'image, sans rien inventer).
 *
 * - [composeNotificationImage] (AJOUTÉ 20/09/2026, demande de Yann) : image ronde pour la
 *   complication SMALL_IMAGE distincte "Notification" (voir NotificationComplicationService),
 *   pensée pour le rond central du tableau de bord Samsung. Même principe général que
 *   [composeRoundImage] (fond circulaire plein, texte blanc gras + contour noir), en 3 zones
 *   empilées verticalement :
 *   1. image de la notif + icône de l'app — CÔTE À CÔTE quand les deux existent (image à gauche,
 *      icône à droite : l'icône ne doit PAS être superposée à l'image, contrairement au badge de
 *      coin de la vue "Toutes notifs" du widget téléphone — Yann, 20/09/2026), l'un ou l'autre
 *      seul centré sur toute la largeur s'il n'y en a qu'un ("si que icône, laisser au milieu"),
 *      pastille placeholder si aucun des deux.
 *   2. titre sur une ligne ([drawFittedText], `ellipsizeIfNeeded = true`).
 *   3. texte de la notif sur jusqu'à 3 lignes ([drawWrappedBodyText], via StaticLayout), taille
 *      ajustée automatiquement entre NOTIF_BODY_MIN_TEXT_SIZE et NOTIF_BODY_MAX_TEXT_SIZE : la
 *      plus grande qui tient à la fois en NOTIF_BODY_MAX_LINES lignes ET sous le budget vertical
 *      NOTIF_BODY_MAX_HEIGHT_PX (un texte court est donc affiché GRAND, un texte long est réduit
 *      jusqu'au plancher puis tronqué avec "…" plutôt que de continuer à rapetisser — Yann,
 *      20/09/2026 : "quand message trop long, on n'arrive pas à lire [...] quand message ou
 *      titre court, ne pas hésiter à faire plus gros"). Même chose pour le titre : plancher/
 *      plafond relevés, l'algorithme de [drawFittedText] utilisant déjà le plafond en premier,
 *      un titre court s'affiche donc déjà au plus grand.
 */
object ComplicationImageComposer {

    // Constantes de composeRoundImage — image UNIQUE pour le rond
    // Dashboard Samsung en SMALL_IMAGE (voir ScoreComplicationService.
    // buildSmallImage). Fond circulaire plein peint sous tout le contenu
    // (nécessaire pour rester visible quel que soit le cadran derrière la
    // complication — voir l'historique README du 15/09/2026, section
    // "SMALL_IMAGE : fond plein + tout sur une ligne").
    private const val ROUND_SIZE = 320
    private const val ROUND_CENTER = ROUND_SIZE / 2f
    // UPDATED 24/09/2026: every SMALL_IMAGE is drawn on a pure black disc ([drawBlackBackground])
    // — a transparent image let the watch face's default grey slot background show through (Yann,
    // capture à l'appui). The old #1B1F27 disc is gone too.
    private const val ROUND_RADIUS = ROUND_SIZE / 2f

    // Ligne du HAUT — image de notif Sofascore (ratio conservé, voir
    // drawFittedBitmap) ou, à défaut (extraction échouée côté téléphone),
    // une pastille placeholder unique (voir drawPlaceholder — plus de
    // repli "deux logos API" depuis le 16/09/2026, voir doc de classe).
    // Cadre agrandi le 16/09/2026 (retour de Yann : "les logos doivent
    // être plus gros") — la ligne du score a été descendue pour lui
    // laisser la place (voir plus bas) plutôt que de la faire déborder du
    // cercle. Vérifié au pire cas (coin du cadre 180×86, le plus loin du
    // centre du cercle) : distance ≈149px pour un rayon de 160px, marge de
    // sécurité ~11px.
    private const val TOP_ROW_CENTER_Y = ROUND_CENTER - 76f
    private const val TOP_NOTIF_MAX_WIDTH = 180f
    private const val TOP_NOTIF_MAX_HEIGHT = 86f
    private const val TOP_PLACEHOLDER_RADIUS = 40f

    // Ligne du MILIEU — score, encore agrandi et descendu le 16/09/2026
    // (retour de Yann : "le score doit être plus gros et peut être mis
    // plus bas pour laisser de la place aux logos"). Toujours un bon
    // espace au-dessus avec la ligne du haut agrandie (~28px de marge) et
    // en dessous avec la ligne du bas (~40px). maxWidth généreux (la
    // ligne du centre est la corde la plus large du cercle à ce décalage
    // encore modeste) : le repli sur drawFittedText ne sert que de
    // garde-fou pour un score à deux chiffres des deux côtés + crochets
    // (ex. "[12]-11") — vérifié au pire cas (maxWidth atteint) : marge
    // ~12px sous le rayon du cercle.
    private const val SCORE_CENTER_Y = ROUND_CENTER + 28f
    private const val SCORE_MAX_TEXT_SIZE = 76f
    private const val SCORE_MIN_TEXT_SIZE = 46f
    private const val SCORE_MAX_TEXT_WIDTH = 270f
    private const val SCORE_STROKE_WIDTH = 8f

    // Ligne du BAS — période/statut (MatchClock.label), légèrement
    // agrandie le 16/09/2026 (retour de Yann), toujours plus petite que
    // le score. Absente si MatchClock.label renvoie une chaîne vide (voir
    // MatchClock.kt). Décalage et largeur max légèrement resserrés par
    // rapport à la version précédente (106px/220px) pour compenser
    // l'agrandissement du texte et garder de la marge sous le rayon du
    // cercle (vérifié au pire cas, texte au maxWidth ET à la taille
    // maximale non réduite simultanément : marge ~7px).
    private const val PERIOD_CENTER_Y = ROUND_CENTER + 100f
    private const val PERIOD_MAX_TEXT_SIZE = 34f
    private const val PERIOD_MIN_TEXT_SIZE = 18f
    private const val PERIOD_MAX_TEXT_WIDTH = 200f
    private const val PERIOD_STROKE_WIDTH = 5f

    // ==== Constantes de composeNotificationImage (complication "Notification", AJOUTÉE 20/09/2026) ====

    // Ligne du HAUT — REVU 20/09/2026 (Yann : "le logo de l'application ne doit pas être sur
    // l'image donc s'il y a une image décaler image sur gauche et icône sur droite. Si que icône,
    // laisser au milieu") : plus de badge d'icône superposé au coin de l'image (contrairement à la
    // vue "Toutes notifs" du widget téléphone, qui superpose — Yann veut ici un traitement
    // différent). Même NOTIF_IMAGE_CENTER_Y pour les 3 cas ci-dessous.
    private const val NOTIF_IMAGE_CENTER_Y = ROUND_CENTER - 90f

    // Cas 1 : image ET icône présentes — côte à côte. Vérifié au pire cas (coin le plus loin du
    // centre du cercle) : marge ~7px pour l'image (décalée à gauche, donc plus proche du bord que
    // si elle était centrée), ~19px pour l'icône (décalée à droite, mais petite et ronde).
    private const val NOTIF_SPLIT_IMAGE_CENTER_X = ROUND_CENTER - 40f
    private const val NOTIF_SPLIT_IMAGE_MAX_WIDTH = 90f
    private const val NOTIF_SPLIT_IMAGE_MAX_HEIGHT = 74f
    private const val NOTIF_SPLIT_ICON_CENTER_X = ROUND_CENTER + 50f
    private const val NOTIF_ICON_RADIUS = 34f

    // Cas 2 : un seul des deux (image seule, ou icône seule) — centré sur toute la largeur
    // ("si que icône, laisser au milieu"). Valeurs d'avant le 20/09/2026, déjà vérifiées sûres
    // (marge ~5.5px).
    private const val NOTIF_IMAGE_ALONE_MAX_WIDTH = 170f
    private const val NOTIF_IMAGE_ALONE_MAX_HEIGHT = 78f
    // Cas 3 (ni l'un ni l'autre) : pastille placeholder, réutilise TOP_PLACEHOLDER_RADIUS ci-dessus.

    // Ligne du TITRE — une seule ligne. [drawFittedText] part de NOTIF_TITLE_MAX_TEXT_SIZE et ne
    // réduit QUE si le texte ne tient pas à cette taille, donc un titre court s'affiche déjà au
    // plus grand sans rien à changer ici pour "faire plus gros quand court" (Yann, 20/09/2026).
    // NOTIF_TITLE_MIN_TEXT_SIZE relevé le même jour (20 -> 28, "définir une taille minimale [...]
    // plus importante que l'actuelle") : un titre trop long pour tenir même réduit est tronqué
    // avec "…" à cette taille plutôt que de continuer à rapetisser (ellipsizeIfNeeded = true).
    // Proche du centre du cercle (corde la plus large disponible) : marge large même au plafond
    // relevé (32 -> 40) — vérifié au pire cas : ~30px.
    private const val NOTIF_TITLE_CENTER_Y = ROUND_CENTER - 22f
    private const val NOTIF_TITLE_MAX_TEXT_SIZE = 40f
    private const val NOTIF_TITLE_MIN_TEXT_SIZE = 28f
    private const val NOTIF_TITLE_MAX_WIDTH = 250f
    private const val NOTIF_TITLE_STROKE_WIDTH = 4f

    // Corps du texte — jusqu'à NOTIF_BODY_MAX_LINES=3 lignes. REVU 20/09/2026 (Yann : "définir une
    // taille minimale du texte qui doit [être] plus importante que l'actuelle [...] quand message
    // ou titre court, ne pas hésiter à faire plus gros") : NOTIF_BODY_MIN_TEXT_SIZE relevé
    // (16 -> 24, le plancher de lisibilité — un message trop long pour tenir même à cette taille
    // est tronqué avec "…" plutôt que de continuer à rapetisser sous ce seuil) et
    // NOTIF_BODY_MAX_TEXT_SIZE relevé (28 -> 34, pour qu'un message court s'affiche nettement plus
    // gros et occupe l'espace disponible).
    //
    // NOTIF_BODY_MAX_HEIGHT_PX (NOUVEAU 20/09/2026) : un plafond de police plus haut change tout
    // pour [drawWrappedBodyText] — à taille fixe, plus de lignes = plus de hauteur, MAIS à nombre
    // de lignes fixe (ex. 3), une police plus grande occupe AUSSI plus de hauteur (chaque ligne est
    // plus haute). Se limiter à vérifier "<= NOTIF_BODY_MAX_LINES lignes" ne suffit donc plus à
    // garantir qu'on reste sous le rayon du cercle une fois NOTIF_BODY_MAX_TEXT_SIZE relevé : un
    // texte qui tient pile en 3 lignes AU PLAFOND déborderait. drawWrappedBodyText vérifie donc
    // aussi la hauteur réelle (StaticLayout.getHeight()) contre ce budget, et réduit la taille tant
    // que ce n'est pas le cas — un texte court (peu de lignes même au plafond) profite du plafond
    // relevé, un texte plus long est automatiquement ramené à une taille qui tient. Calculé pour
    // NOTIF_BODY_MAX_WIDTH=210 (corde disponible à ce décalage vertical, marge de sécurité
    // incluse) ; NOTIF_BODY_MIN_TEXT_SIZE=24 sur 3 lignes tient largement dedans (~86px sur un
    // budget de 105px), donc le repli tronqué en dernier recours ne peut jamais déborder non plus.
    private const val NOTIF_BODY_TOP_Y = ROUND_CENTER + 4f
    private const val NOTIF_BODY_MAX_WIDTH = 210f
    private const val NOTIF_BODY_MAX_LINES = 3
    private const val NOTIF_BODY_MAX_HEIGHT_PX = 105f
    private const val NOTIF_BODY_MAX_TEXT_SIZE = 34f
    private const val NOTIF_BODY_MIN_TEXT_SIZE = 24f
    private const val NOTIF_BODY_LINE_SPACING_MULT = 1.0f
    private const val NOTIF_BODY_STROKE_WIDTH = 4f

    /**
     * Image UNIQUE pour le rond Dashboard Samsung en SMALL_IMAGE (voir
     * ScoreComplicationService.buildSmallImage) — fond circulaire plein,
     * puis trois lignes empilées verticalement : image de notif Sofascore
     * (ou une pastille placeholder) en haut, score au milieu en grand,
     * période en bas en plus petit. Voir le commentaire de tête de fichier
     * pour le détail de la revue du 16/09/2026.
     *
     * Contenu de la ligne du haut : [MatchScore.notifImage] si disponible
     * (image déjà combinée par Sofascore lui-même, dessinée en conservant
     * son ratio d'origine via [drawFittedBitmap] — fini l'étirement
     * 190×100 de la version précédente, qui l'aplatissait) ; sinon une
     * pastille placeholder unique (via [drawPlaceholder] — plus de repli
     * "deux logos API" depuis le 16/09/2026, voir doc de classe).
     */
    fun composeRoundImage(match: MatchScore?): Bitmap {
        val bitmap = Bitmap.createBitmap(ROUND_SIZE, ROUND_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        drawBlackBackground(canvas)

        val notifImage = match?.notifImage
        if (notifImage != null) {
            drawFittedBitmap(canvas, notifImage, ROUND_CENTER, TOP_ROW_CENTER_Y, TOP_NOTIF_MAX_WIDTH, TOP_NOTIF_MAX_HEIGHT)
        } else {
            drawPlaceholder(canvas, ROUND_CENTER, TOP_ROW_CENTER_Y, TOP_PLACEHOLDER_RADIUS)
        }

        drawFittedText(
            canvas, match?.scoreText() ?: "vs",
            ROUND_CENTER, SCORE_CENTER_Y,
            SCORE_MAX_TEXT_SIZE, SCORE_MIN_TEXT_SIZE, SCORE_MAX_TEXT_WIDTH, SCORE_STROKE_WIDTH
        )

        val periodLabel = match?.let { MatchClock.label(it) }.orEmpty()
        if (periodLabel.isNotBlank()) {
            drawFittedText(
                canvas, periodLabel,
                ROUND_CENTER, PERIOD_CENTER_Y,
                PERIOD_MAX_TEXT_SIZE, PERIOD_MIN_TEXT_SIZE, PERIOD_MAX_TEXT_WIDTH, PERIOD_STROKE_WIDTH
            )
        }

        return bitmap
    }

    /**
     * Image UNIQUE pour la complication ronde "Notification" (AJOUTÉ 20/09/2026, demande de
     * Yann) — pensée pour le rond central du tableau de bord Samsung, distincte de
     * [composeRoundImage]/"Score en direct". Fond circulaire plein (même couleur que
     * [composeRoundImage]), puis 3 zones empilées verticalement :
     * 1. image de la notif + icône de l'app, CÔTE À CÔTE quand les deux existent (image à
     *    gauche, icône ronde à droite — [drawCircularIcon] — REVU 20/09/2026 : l'icône ne doit
     *    plus être superposée à l'image). Un seul des deux : il est centré, sur toute la largeur
     *    ("si que icône, laisser au milieu"). Aucun des deux : pastille placeholder
     *    ([drawPlaceholder]).
     * 2. titre sur une seule ligne, réduit puis tronqué en dernier recours
     *    ([drawFittedText], `ellipsizeIfNeeded = true`).
     * 3. texte de la notif sur jusqu'à 3 lignes, taille ajustée automatiquement (plus grand
     *    possible pour un texte court, réduit jusqu'au plancher puis tronqué pour un texte long —
     *    voir NOTIF_BODY_MAX_HEIGHT_PX ci-dessus) via [drawWrappedBodyText].
     *
     * `notification == null` (rien reçu du téléphone depuis le dernier redémarrage du processus
     * watch, voir NotificationInfoStore) affiche juste "Aucune notification" à la place du corps
     * du texte, pas de titre ni d'image.
     */
    fun composeNotificationImage(notification: NotificationInfo?): Bitmap {
        val bitmap = Bitmap.createBitmap(ROUND_SIZE, ROUND_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        drawBlackBackground(canvas)

        val notifImage = notification?.image
        val appIcon = notification?.appIcon
        when {
            notifImage != null && appIcon != null -> {
                // Les deux existent : côte à côte, image à gauche / icône à droite — l'icône ne
                // doit pas être superposée à l'image (Yann, 20/09/2026).
                drawFittedBitmap(canvas, notifImage, NOTIF_SPLIT_IMAGE_CENTER_X, NOTIF_IMAGE_CENTER_Y, NOTIF_SPLIT_IMAGE_MAX_WIDTH, NOTIF_SPLIT_IMAGE_MAX_HEIGHT)
                drawCircularIcon(canvas, appIcon, NOTIF_SPLIT_ICON_CENTER_X, NOTIF_IMAGE_CENTER_Y, NOTIF_ICON_RADIUS)
            }
            notifImage != null -> {
                // Pas d'icône disponible : image seule, centrée sur toute la largeur.
                drawFittedBitmap(canvas, notifImage, ROUND_CENTER, NOTIF_IMAGE_CENTER_Y, NOTIF_IMAGE_ALONE_MAX_WIDTH, NOTIF_IMAGE_ALONE_MAX_HEIGHT)
            }
            appIcon != null -> {
                // Pas d'image de notif : icône seule, centrée ("si que icône, laisser au
                // milieu") — remplit directement la zone, même règle que le widget téléphone
                // (NowBarWidgetProvider.applyAllNotifSlotAsGeneric).
                drawFittedBitmap(canvas, appIcon, ROUND_CENTER, NOTIF_IMAGE_CENTER_Y, NOTIF_IMAGE_ALONE_MAX_WIDTH, NOTIF_IMAGE_ALONE_MAX_HEIGHT)
            }
            else -> drawPlaceholder(canvas, ROUND_CENTER, NOTIF_IMAGE_CENTER_Y, TOP_PLACEHOLDER_RADIUS)
        }

        val title = notification?.title.orEmpty()
        if (title.isNotBlank()) {
            drawFittedText(
                canvas, title,
                ROUND_CENTER, NOTIF_TITLE_CENTER_Y,
                NOTIF_TITLE_MAX_TEXT_SIZE, NOTIF_TITLE_MIN_TEXT_SIZE, NOTIF_TITLE_MAX_WIDTH, NOTIF_TITLE_STROKE_WIDTH,
                ellipsizeIfNeeded = true
            )
        }

        val body = notification?.text.orEmpty()
        if (body.isNotBlank()) {
            drawWrappedBodyText(
                canvas, body,
                ROUND_CENTER, NOTIF_BODY_TOP_Y,
                NOTIF_BODY_MAX_WIDTH, NOTIF_BODY_MAX_LINES, NOTIF_BODY_MAX_HEIGHT_PX,
                NOTIF_BODY_MAX_TEXT_SIZE, NOTIF_BODY_MIN_TEXT_SIZE,
                NOTIF_BODY_LINE_SPACING_MULT, NOTIF_BODY_STROKE_WIDTH
            )
        } else if (notification == null) {
            drawFittedText(
                canvas, "Aucune notification",
                ROUND_CENTER, NOTIF_TITLE_CENTER_Y + 44f,
                26f, 20f, NOTIF_BODY_MAX_WIDTH, 3f,
                ellipsizeIfNeeded = true
            )
        }

        return bitmap
    }

    /**
     * NEW 24/09/2026 — "Messages" complication: up to 4 contact circles (most recent first: top-left,
     * top-right, bottom-left, bottom-right; 1–3 messages are laid out centered), each with the source
     * app's icon as a badge at its bottom-right. Black background. A message without a contact
     * photo gets a colored disc with its title's initial.
     */
    fun composeMessagesImage(messages: List<MessageInfo>): Bitmap {
        val bitmap = Bitmap.createBitmap(ROUND_SIZE, ROUND_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawBlackBackground(canvas)
        val shown = messages.take(4)
        if (shown.isEmpty()) {
            // UPDATED 24/09/2026 (Yann: the WhatsApp outline without the handset, same size as
            // WhatsApp's own complication — measured on his screenshot: glyph ~0.6 r wide, centered
            // 0.39 r above the middle; count ~0.43 r tall, 0.33 r below).
            drawChatBubble(canvas, ROUND_CENTER, ROUND_CENTER - 62f, 43f)
            drawFittedText(canvas, "0", ROUND_CENTER, ROUND_CENTER + 53f, 96f, 70f, 200f, 6f)
            return bitmap
        }
        // (dx, dy) offsets from the center + contact radius — all fit inside the 160 px round.
        val (slots, radius) = when (shown.size) {
            1 -> listOf(0f to 0f) to 100f
            2 -> listOf(-72f to 0f, 72f to 0f) to 64f
            3 -> listOf(-68f to -58f, 68f to -58f, 0f to 66f) to 60f
            else -> listOf(-68f to -68f, 68f to -68f, -68f to 68f, 68f to 68f) to 60f
        }
        shown.forEachIndexed { index, message ->
            val cx = ROUND_CENTER + slots[index].first
            val cy = ROUND_CENTER + slots[index].second
            val image = message.image
            if (image != null) {
                drawCircularIcon(canvas, image, cx, cy, radius)
            } else {
                drawInitialDisc(canvas, message.title, cx, cy, radius)
            }
            message.appIcon?.let { icon ->
                val badgeRadius = radius * 0.34f
                val bx = cx + radius * 0.48f
                val by = cy + radius * 0.48f
                val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
                canvas.drawCircle(bx, by, badgeRadius + 3f, ring)
                drawCircularIcon(canvas, icon, bx, by, badgeRadius)
            }
        }
        return bitmap
    }

    /** Pure black full disc behind every SMALL_IMAGE (hides the watch face's grey slot background). */
    private fun drawBlackBackground(canvas: Canvas) {
        canvas.drawCircle(ROUND_CENTER, ROUND_CENTER, ROUND_RADIUS, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
    }

    /**
     * WhatsApp-style chat bubble outline without the handset: a circle of [radius] around
     * ([cx], [cy]) whose bottom-left opens into a small pointed tail. White stroke with a thin
     * dark outline, same style as the rest of the complication glyphs.
     */
    private fun drawChatBubble(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        fun onCircle(deg: Double) = Pair(
            cx + radius * Math.cos(Math.toRadians(deg)).toFloat(),
            cy + radius * Math.sin(Math.toRadians(deg)).toFloat()
        )
        // Canvas angles: 0° = right, clockwise. The tail sits between 112° and 152° (bottom-left).
        val (sx, sy) = onCircle(112.0)
        val (ex, ey) = onCircle(152.0)
        val bubble = android.graphics.Path().apply {
            moveTo(sx, sy)
            lineTo(cx - radius * 1.02f, cy + radius * 1.02f)   // tail tip
            lineTo(ex, ey)
            arcTo(RectF(cx - radius, cy - radius, cx + radius, cy + radius), 152f, 320f, false)
            close()
        }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            color = Color.BLACK
            strokeWidth = 12f
        }
        val stroke = Paint(outline).apply { color = Color.WHITE; strokeWidth = 7f }
        canvas.drawPath(bubble, outline)
        canvas.drawPath(bubble, stroke)
    }

    private val INITIAL_COLORS = intArrayOf(
        Color.parseColor("#3F7CF4"), Color.parseColor("#2FA86B"), Color.parseColor("#E0803A"),
        Color.parseColor("#9A5BD6"), Color.parseColor("#D65B7C"), Color.parseColor("#3AA6B9")
    )

    private fun drawInitialDisc(canvas: Canvas, title: String, cx: Float, cy: Float, radius: Float) {
        val color = INITIAL_COLORS[(title.hashCode() and 0x7fffffff) % INITIAL_COLORS.size]
        canvas.drawCircle(cx, cy, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        val initial = title.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
            textSize = radius
        }
        val bounds = Rect()
        paint.getTextBounds(initial, 0, initial.length, bounds)
        canvas.drawText(initial, cx, cy - bounds.exactCenterY(), paint)
    }

    /**
     * Dessine [bitmap] centré sur ([centerX], [centerY]) en conservant son
     * ratio d'origine, mis à l'échelle pour tenir dans [maxWidth]×[maxHeight]
     * (jamais étiré hors de son ratio réel) — remplace, depuis le
     * 16/09/2026, l'étirement dans un cadre fixe 190×100 qui aplatissait
     * l'image de notif Sofascore (voir le commentaire de tête de fichier).
     *
     * Renvoie le rectangle de destination réellement dessiné — actuellement inutilisé par les
     * deux appelants (composeRoundImage, composeNotificationImage), gardé pour un futur repli
     * qui aurait besoin de connaître la taille réelle une fois le ratio appliqué.
     */
    private fun drawFittedBitmap(canvas: Canvas, bitmap: Bitmap, centerX: Float, centerY: Float, maxWidth: Float, maxHeight: Float): RectF {
        val scale = minOf(maxWidth / bitmap.width, maxHeight / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val dest = RectF(centerX - width / 2f, centerY - height / 2f, centerX + width / 2f, centerY + height / 2f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(bitmap, null, dest, paint)
        return dest
    }

    /**
     * Dessine [text] centré sur ([centerX], [centerY]), blanc gras avec
     * contour noir (lisible par-dessus une image ou un fond quelconque).
     * Part de [maxTextSize] (donc un texte court s'affiche déjà à la taille maximale, sans rien
     * à faire de plus) et réduit jusqu'à [minTextSize] si le texte mesuré dépasse [maxWidth] —
     * garde-fou pour les libellés de période les plus longs (ex. "Prolongation", "Forfait
     * technique") sans jamais dépasser la corde du cercle disponible à cette hauteur, et pour un
     * score à deux chiffres des deux côtés + crochets.
     *
     * [ellipsizeIfNeeded] (AJOUTÉ 20/09/2026, `false` par défaut — composeRoundImage n'en a pas
     * besoin, ses libellés sont toujours courts) : si `true` et que le texte dépasse encore
     * [maxWidth] une fois réduit à [minTextSize] (titre de notif arbitrairement long), tronque
     * avec "…" plutôt que de laisser le texte déborder du cercle.
     */
    private fun drawFittedText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        centerY: Float,
        maxTextSize: Float,
        minTextSize: Float,
        maxWidth: Float,
        strokeWidthPx: Float,
        ellipsizeIfNeeded: Boolean = false
    ) {
        // TextPaint (pas juste Paint) : requis par TextUtils.ellipsize ci-dessous, qui n'accepte
        // qu'un TextPaint — sans effet sur measureText/getTextBounds/drawText, qui fonctionnent
        // pareil sur les deux (TextPaint hérite de Paint).
        val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
            textSize = maxTextSize
        }
        var measuredWidth = fillPaint.measureText(text)
        if (measuredWidth > maxWidth) {
            fillPaint.textSize = (maxTextSize * (maxWidth / measuredWidth)).coerceAtLeast(minTextSize)
            measuredWidth = fillPaint.measureText(text)
        }
        var displayText = text
        if (ellipsizeIfNeeded && measuredWidth > maxWidth) {
            displayText = TextUtils.ellipsize(text, fillPaint, maxWidth, TextUtils.TruncateAt.END).toString()
        }
        val strokePaint = Paint(fillPaint).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            color = Color.BLACK
        }
        val bounds = Rect()
        fillPaint.getTextBounds(displayText, 0, displayText.length, bounds)
        val baselineY = centerY - bounds.exactCenterY()
        canvas.drawText(displayText, centerX, baselineY, strokePaint)
        canvas.drawText(displayText, centerX, baselineY, fillPaint)
    }

    /**
     * Corps de texte sur jusqu'à [maxLines] lignes (AJOUTÉ 20/09/2026 pour composeNotificationImage)
     * — cherche la plus grande taille entre [maxTextSize] et [minTextSize] (pas de 1px) pour
     * laquelle [text], une fois retourné à la ligne dans [maxWidth], tient à la fois en
     * [maxLines] lignes ET sous [maxHeightPx] de hauteur réelle
     * (`StaticLayout.getHeight()` — REVU 20/09/2026 : un plafond de police plus haut fait qu'à
     * nombre de lignes fixe, une police plus grande prend aussi plus de hauteur, donc vérifier
     * seulement le nombre de lignes ne suffit plus à garantir qu'on reste sous le rayon du
     * cercle — voir NOTIF_BODY_MAX_HEIGHT_PX). Un texte court est ainsi affiché à une taille
     * nettement plus grande, un texte long est ramené à une taille qui tient ("afficher le
     * plus de caractères possible tout en restant lisible, et ne pas hésiter à faire plus gros
     * pour un texte court", demande de Yann). Si même [minTextSize] ne suffit pas (le texte ne
     * tient toujours pas en [maxLines] lignes à cette taille), tronque la dernière ligne avec
     * "…" (StaticLayout.Builder.setEllipsize) plutôt que de continuer à réduire sous le seuil de
     * lisibilité ou de déborder du cercle — [minTextSize] est choisi pour que [maxLines] lignes à
     * cette taille tiennent toujours sous [maxHeightPx] (voir la doc des constantes), donc ce
     * repli ne redéborde jamais.
     *
     * Centré horizontalement (Layout.Alignment.ALIGN_CENTER) — StaticLayout ignore
     * Paint.textAlign, d'où le canvas.translate vers le coin haut-gauche du bloc plutôt que
     * [centerX] directement. Même technique contour noir + remplissage blanc que
     * [drawFittedText] (deux passes de layout.draw avec le style du Paint changé entre les
     * deux, la mesure/le retour à la ligne ne dépendant pas du style du trait).
     */
    private fun drawWrappedBodyText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        topY: Float,
        maxWidth: Float,
        maxLines: Int,
        maxHeightPx: Float,
        maxTextSize: Float,
        minTextSize: Float,
        lineSpacingMultiplier: Float,
        strokeWidthPx: Float
    ) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val widthPx = maxWidth.toInt().coerceAtLeast(1)

        var chosenLayout: StaticLayout? = null
        var size = maxTextSize
        while (size >= minTextSize) {
            paint.textSize = size
            val candidate = StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, lineSpacingMultiplier)
                .setIncludePad(false)
                .build()
            if (candidate.lineCount <= maxLines && candidate.height <= maxHeightPx) {
                chosenLayout = candidate
                break
            }
            size -= 1f
        }

        val layout = chosenLayout ?: run {
            // Même à minTextSize, le texte complet ne tient pas (maxLines lignes ET maxHeightPx)
            // — tronque la dernière ligne avec "…" plutôt que de déborder du cercle. minTextSize
            // est choisi pour que maxLines lignes à cette taille tiennent toujours dans
            // maxHeightPx (voir la doc des constantes), donc ce repli ne redéborde jamais.
            paint.textSize = minTextSize
            StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, lineSpacingMultiplier)
                .setIncludePad(false)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setEllipsizedWidth(widthPx)
                .build()
        }

        val left = centerX - maxWidth / 2f
        canvas.save()
        canvas.translate(left, topY)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidthPx
        paint.color = Color.BLACK
        layout.draw(canvas)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        layout.draw(canvas)
        canvas.restore()
    }

    /**
     * Icône de l'app source recadrée en cercle, dessinée à ([centerX], [centerY]) — utilisée par
     * composeNotificationImage quand l'image de la notif et l'icône sont CÔTE À CÔTE (REVU
     * 20/09/2026 : remplace l'ancien badge de coin superposé à l'image, retiré à la demande de
     * Yann — "le logo de l'application ne doit pas être sur l'image"). BitmapShader plutôt qu'un
     * simple drawBitmap : pas de coins carrés qui dépasseraient du cercle pour une icône non déjà
     * circulaire. Pas d'anneau de fond ici (contrairement à l'ancien badge) : l'icône est posée
     * directement sur le fond du rond (noir depuis le 24/09/2026), pas sur l'image, donc
     * rien à détacher visuellement.
     */
    private fun drawCircularIcon(canvas: Canvas, bitmap: Bitmap, centerX: Float, centerY: Float, radius: Float) {
        val scale = (radius * 2f) / minOf(bitmap.width, bitmap.height)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(centerX - bitmap.width * scale / 2f, centerY - bitmap.height * scale / 2f)
        }
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(matrix)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        canvas.drawCircle(centerX, centerY, radius, paint)
    }

    /**
     * Repli quand [MatchScore.notifImage] est indisponible (extraction
     * échouée côté téléphone) — une seule pastille centrée, depuis que
     * Sofascore est la SEULE source d'image (16/09/2026, voir doc de
     * classe) : plus de logos séparés à afficher côte à côte. #5A6478
     * (pas #3A4150, presque invisible sur le fond #1B1F27 du rond — voir
     * README, section "SMALL_IMAGE : fond plein + tout sur une ligne") :
     * montrer qu'il n'y a pas d'image, plutôt qu'un trou quasi
     * indiscernable du fond. Réutilisé tel quel par composeNotificationImage
     * quand ni l'image ni l'icône ne sont disponibles.
     */
    private fun drawPlaceholder(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#5A6478")
        }
        canvas.drawCircle(centerX, centerY, radius, paint)
    }
}
