package com.yann.nowbarmirror.sport

/**
 * Transforme les lignes d'une notification Sofascore (une notif = un match,
 * voir SofascoreNotificationListenerService) en un [MatchResult] de repli.
 * Détecte le sport à partir du contenu des lignes (pas d'indicateur dédié
 * dans la notif) :
 * - présence d'une ligne "Match terminé : H - A" -> score final, peu
 *   importe le sport (voir [parse] pour pourquoi c'est vérifié EN PREMIER,
 *   avant même de détecter le sport)
 * - sinon, présence d'au moins une ligne "quart-temps" -> basket
 *   ([parseBasketball])
 * - sinon, présence d'au moins une ligne "Xe set terminé" ET la somme des
 *   deux scores de la plus RÉCENTE de ces lignes égale son numéro d'ordre
 *   (ex. "3e set terminé : 2 - 1" -> 2+1=3) -> sport à décompte de sets
 *   cumulatif ([parseSetTally] — tennis de table, volley)
 * - sinon, présence d'au moins une ligne "Xe set terminé" SANS cette
 *   égalité -> tennis ([parseTennis])
 * - sinon -> gabarit foot ([parseFootball], sert aussi au handball — mêmes
 *   libellés "Mi-temps"/"2de mi-temps a commencé"/"Match terminé" dans les
 *   deux sports, rien à distinguer)
 *
 * [lines] doit être trié DU PLUS RÉCENT AU PLUS ANCIEN (comme affiché dans
 * le panneau de notifications).
 *
 * **Le score de "Match terminé : H - A" est TOUJOURS prioritaire dès qu'il
 * est présent, peu importe sa position dans [lines]** (voir [parse]) :
 * confirmé nécessaire par deux captures réelles du 15/09/2026 où l'ordre
 * chronologique naturel n'est pas respecté par Sofascore :
 * - Volley (Macédoine du Nord - Portugal) : "3e set terminé : 0 - [3]
 *   Portugal" arrive APRÈS "Match terminé : 0 - 3" (donc affiché AU-DESSUS,
 *   plus récent), alors que logiquement le match ne devrait se terminer
 *   qu'une fois le 3e set fini.
 * - Basket (Glint Korfez - Kolossos Rodou) : le match passe directement de
 *   "3e quart-temps terminé : 66 - 70" à "Match terminé : 91 - 79" — AUCUNE
 *   ligne "4e quart-temps..." (ni début ni fin) n'est jamais postée
 *   séparément. Reconstruire le score à partir de la dernière période
 *   connue donnerait alors 66-70 (score de fin de 3e quart-temps) au lieu
 *   du vrai score final 91-79.
 * Reconstruire le score à partir des lignes de période (sets, quarts-temps)
 * serait donc, dans les deux cas, soit dans le désordre, soit tout
 * simplement incomplet — alors que "Match terminé" donne toujours le bon
 * score directement, quel que soit le sport.
 *
 * Un match qui vient de démarrer (coup d'envoi) affiche 0-0 — la notif
 * Sofascore confirmée pour cet événement ("Match commencé", sans score) ne
 * le précise pas, voir [firstHalfStarted]. **Ambiguïté entre sports à ce
 * stade précis** : "Match commencé" est le MÊME libellé dans tous les
 * sports (confirmé, voir les exemples plus bas) et rien ne les distingue
 * tant qu'aucun quart-temps/set n'est encore terminé — un match à
 * quarts-temps ou à sets qui vient tout juste de démarrer est donc affiché
 * avec le gabarit foot (0-0, statut "P1" côté montre) jusqu'à la fin du 1er
 * quart-temps/set, où le bon vocabulaire prend le relais automatiquement.
 * Léger défaut d'affichage temporaire, sans conséquence au-delà de
 * quelques minutes en début de match.
 *
 * Exemple réel observé pour le foot (capture d'écran, 12/09/2026, Real
 * Madrid - Rayo Vallecano) :
 *   "Match terminé : 4 - 1"
 *   "90' But : [4] - 1  Kylian Mbappé"
 *   "51' But : 3 - [1]  Sergio Camello"
 *   "2de mi-temps a commencé: 3 - 0"
 *   "Mi-temps : 3 - 0"
 *   "35' But : [3] - 0  Jude Bellingham"
 * Les crochets entourent le score de l'équipe qui vient de marquer —
 * utilisés pour renseigner [MatchResult.lastScorer] ("home"/"away", voir
 * [bracketedSide]), affiché entre crochets côté montre (demandé de nouveau
 * par Yann le 15/09/2026 — voir wear/MatchScore.kt/scoreText). Ça confirme
 * aussi que le PREMIER nombre est toujours le score domicile.
 *
 * Exemple réel observé pour le foot SANS minute ni buteur précis (capture
 * d'écran, 15/09/2026, Kasuka FC - Karketu Díli, ligue moins couverte par
 * Sofascore) :
 *   "But: [2] - 0 Kasuka FC"
 * Le nom qui suit est ici celui de l'ÉQUIPE, pas d'un joueur — sans
 * conséquence, ce nom n'est de toute façon jamais exploité (ni ici, ni
 * dans [timedEvent]) — voir [goalNoMinute].
 *
 * Exemple réel observé pour une CORRECTION de score au foot (capture
 * d'écran, 15/09/2026, Elche - Real Madrid) :
 *   "Mi-temps : 0 - 2"
 *   "Correction du score : 0 - 2"
 *   "But: 0 - [3] Real Madrid"
 *   "33' But : 0 - [2]  Kylian Mbappé"
 *   "25' But : 0 - [1] Matías Dituro (contre son camp)"
 *   "Match commencé"
 * Un 3e but ("But: 0-[3]") a ici été invalidé après coup (VAR ?) : Sofascore
 * pousse une ligne "Correction du score : H - A" avec le score corrigé (ici
 * revenu à 0-2), voir [correctionScore]. Dans CET exemple précis, "Mi-temps
 * : 0 - 2" (encore plus récent) suffisait déjà à afficher le bon score sans
 * même avoir besoin de reconnaître "Correction du score" — mais si cette
 * ligne de correction était la plus récente ligne reconnue (pas de nouvelle
 * mi-temps entre-temps), il faut la reconnaître explicitement pour ne pas
 * retomber sur le but annulé.
 *
 * Exemple réel observé pour le handball (capture d'écran, 15/09/2026, Spor
 * Toto - Vogošća) :
 *   "Match terminé : 37 - 21"
 *   "2de mi-temps a commencé: 20 - 9"
 *   "Mi-temps : 20 - 9"
 *   "Match commencé"
 * Mêmes libellés qu'au foot (mi-temps, pas de quart-temps) — [parseFootball]
 * suffit déjà, aucun code dédié nécessaire.
 *
 * Exemple réel observé pour le basket (capture d'écran, 15/09/2026, Glint
 * Korfez - Kolossos Rodou) :
 *   "Match terminé : 91 - 79"
 *   "3e quart-temps terminé : 66 - 70"
 *   "3e quart-temps a commencé: 43 - 41"
 *   "Mi-temps terminé : 43 - 41"
 *   "1er quart-temps terminé : 26 - 21"
 *   "Match commencé"
 * Voir [parseBasketball]. Pas de crochets observés sur ces lignes
 * (contrairement au foot/volley/tennis) : le basket ne renseigne jamais
 * [MatchResult.lastScorer].
 *
 * Exemple réel observé pour le tennis (capture d'écran, 14/09/2026,
 * E. Jacquemot - L. Samsonova) :
 *   "1er set terminé : 6 - [7] L. Samsonova"
 *   "Match commencé"
 * et pour un match complet (15/09/2026, M. Kouamé - R. Matsuda) :
 *   "Match terminé : 2 - 0 M. Kouamé"
 *   "2d set terminé : [6] - 3 M. Kouamé"
 *   "1er set terminé : [6] - 1 M. Kouamé"
 *   "Match commencé"
 *
 * Exemple réel observé pour le tennis de table (capture d'écran,
 * 15/09/2026, Choi H. - Wang J.) :
 *   "3e set terminé : [2] - 1 Choi H."
 * Contrairement au tennis, "[2] - 1" n'est PAS le score de jeux/points du
 * set qui vient de finir : c'est déjà le nombre de SETS gagnés par
 * chacun (2 pour Choi H., 1 pour Wang J.) — voir [parseSetTally] pour la
 * façon dont on distingue les deux cas.
 *
 * Exemple réel observé pour le volley (capture d'écran, 15/09/2026,
 * Macédoine du Nord - Portugal) :
 *   "3e set terminé : 0 - [3] Portugal"
 *   "Match terminé : 0 - 3"
 *   "2d set terminé : 0 - [2] Portugal"
 *   "1er set terminé : 0 - [1] Portugal"
 *   "Match commencé"
 * Même modèle qu'au tennis de table (décompte cumulatif de sets, gabarit
 * [parseSetTally] partagé) — confirme aussi que "Match terminé" peut
 * arriver AVANT la ligne de fin du dernier set (voir doc de classe plus
 * haut).
 *
 * Les noms de joueurs sont déjà au format "Initiale. Nom" dans la notif
 * elle-même (titre ET lignes d'événement, tennis ET tennis de table) —
 * rien à transformer côté app.
 *
 * Exemple réel observé pour le rugby (capture d'écran fournie par Yann le
 * 17/09/2026, Aurillac - Brive), du plus ancien au plus récent :
 *   "Match commencé"
 *   "Score : 0 - [5] Brive"
 *   "Score : 0 - [7] Brive"
 *   "Score : [5] - 7 Aurillac"
 *   "Score : [7] - 7 Aurillac"
 * Même gabarit que [goalNoMinute] au foot (pas de minute, un nom en fin de
 * ligne jamais exploité), juste avec le libellé "Score" plutôt que "But" —
 * voir [scoreEvent]. Les deux nombres sont déjà le score total à jour, et
 * le crochet entoure le côté qui vient de marquer, exactement comme au
 * foot : ici Brive marque un essai (5), le transforme (+2 -> 7), puis
 * Aurillac fait de même (5, puis 7) — le score affiché passe donc par
 * 0-5, 0-7, 5-7, 7-7. "Mi-temps"/"Match terminé" au rugby suivent le même
 * libellé qu'au foot (CONFIRMÉ depuis par un exemple réel, capture fournie
 * par Yann le 20/09/2026, Vannes - Toulouse : "2de mi-temps a commencé:
 * 16 - 17" / "Mi-temps : 16 - 17") — [parseFootball] les gère déjà,
 * [scoreEvent] est la seule ligne d'événement propre au rugby.
 *
 * DEMANDÉ par Yann le 20/09/2026 : indiquer la période (1 ou 2) sur les
 * lignes [scoreEvent] elles-mêmes — contrairement au foot ([timedEvent]),
 * ces lignes ne portent aucune minute, donc rien ne dit dans quelle
 * période on se trouve une fois la mi-temps passée. Résolu en cherchant,
 * à partir de la ligne [scoreEvent] la plus récente, la prochaine ligne
 * plus ANCIENNE (donc plus loin dans la liste, triée du plus récent au
 * plus ancien) qui soit un marqueur de période déjà connu — voir
 * [currentHalfStatus].
 *
 * Exemple réel de séance de tirs au but au foot (captures fournies par
 * Yann le 20/09/2026) :
 * - Italie U20 (F) - Chine U20 (F), séance EN COURS :
 *   "Penalty raté : 5 - [4]Chenyu Li"
 *   "Séance de tirs au but : [5] - 4 Gabriella Langella"
 *   "Séance de tirs au but : 4 - [4] Xiao Yafei"
 *   "Séance de tirs au but : [4] - 3 Martina Bressan"
 *   "Séance de tirs au but : 3 - [3] Xinyi Zhou"
 *   "Séance de tirs au but : [3] - 2 Marta Zamboni"
 *   Même gabarit que [goalNoMinute]/[scoreEvent] (score de la séance déjà à
 *   jour, crochet sur le côté qui vient de tirer) — voir [shootoutTick]/
 *   [missedPenalty]. Un tir raté ne change pas le score mais reste posté
 *   avec le même gabarit "H - A" (inchangé). À ce stade (6 lignes captées
 *   par Android, voir SofascoreNotificationListenerService), le score du
 *   match AVANT la séance (temps réglementaire + prolongation) a déjà
 *   défilé hors de la notif dès que la séance dure plus de quelques tirs —
 *   seul le score de la séance elle-même est donc affiché ici, statut
 *   "TAB" (traduit en "PEN" côté montre/widget, voir MatchClock.kt/
 *   SofascoreMatchPresentation.kt — nom interne différent du libellé
 *   affiché pour ne pas entrer en collision avec le "PEN" déjà utilisé par
 *   TheSportsDB pour un tout autre statut, "Fin (tab)").
 * - Brésil U20 (F) - États-Unis U20 (F), match TERMINÉ aux tirs au but :
 *   "Match terminé : 6 - 5 (5 - 4) (AP)"
 *   Voir [matchFinishedWithShootout] : 6-5 est le score final (prolongation
 *   comprise), (5-4) le score de la séance de tirs au but (pas de crochets
 *   dans cet exemple précis, la ligne de gabarit les tolère quand même par
 *   robustesse), "(AP)" confirme la décision aux tirs au but — réutilisé
 *   tel quel comme statut ("AP" est déjà traduit en "Fin (tab)" côté
 *   montre/widget, vocabulaire TheSportsDB existant, coïncidence bienvenue
 *   plutôt que recherchée).
 *
 * Exemple réel de prolongation au foot (capture fournie par Yann le
 * 20/09/2026, Italie U20 (F) - Chine U20 (F)), du plus ancien au plus
 * récent :
 *   "73' But : 1 - [1] Elena Belli (contre son camp)"
 *   "En attente des prolongations : 1 - 1"
 *   "1re prolongation a commencé: 1 - 1"
 *   "Mi-temps des prolongations : 1 - 1"
 *   "2de prolongation a commencé: 1 - 1"
 *   "En attente des pénaltys : 1 - 1"
 * Confirme toute la séquence : temps réglementaire terminé à 1-1 (aucun
 * but marqué depuis, la ligne "But" la plus récente reste celle de la
 * 73e), pause ("En attente des prolongations"), 1re période de
 * prolongation ([extraTimeFirstStarted] -> "ET1"), pause médiane
 * ([extraTimeHalftime] -> "MTP"), 2e période ([extraTimeSecondStarted] ->
 * "ET2"), puis attente des tirs au but ([awaitingPenalties], même statut
 * interne "TAB"/affiché "PEN" que [shootoutTick]/[missedPenalty], voir
 * plus haut) — toujours à 1-1, avant le premier tir. Aucun crochet sur ces
 * lignes de transition (contrairement aux lignes de but/tir), gabarits
 * donc plus simples que le reste du fichier. "En attente des
 * prolongations" (transition SANS équivalent demandé par Yann parmi
 * ET1/MTP/ET2/PEN) réutilise le statut générique "ET" déjà traduit en
 * "Prolongation" côté montre/widget (vocabulaire TheSportsDB existant,
 * voir MatchClock.kt) plutôt que d'inventer un nouveau code d'affichage.
 */
object SofascoreNotificationParser {

    // --- Score final (tous sports) ---

    // Brackets optionnels par cohérence/robustesse avec les autres gabarits
    // de ce fichier, même si aucun exemple réel n'en montre sur cette ligne
    // précise — voir [parse] pour l'usage EN PRIORITÉ, avant toute
    // détection de sport.
    private val matchFinished = Regex(
        """Match termin[ée]\s*:\s*(?:\[(\d+)\]|(\d+))\s*-\s*(?:\[(\d+)\]|(\d+))""",
        RegexOption.IGNORE_CASE
    )

    // --- Foot (sert aussi au handball, voir doc de classe) ---

    private val halfTime = Regex("""Mi-temps\s*:\s*(\d+)\s*-\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val secondHalfStarted = Regex("""2\w*\s*mi-temps a commenc[ée]\s*:\s*(\d+)\s*-\s*(\d+)""", RegexOption.IGNORE_CASE)
    // CONFIRMÉ par un exemple réel (14/09/2026) : Sofascore écrit
    // "Match commencé" — sans "a" (pas "Match A commencé") et sans
    // score accolé. Le "a" et le score restent tous deux optionnels
    // dans le gabarit pour couvrir aussi une éventuelle formulation
    // "Le match a commencé (: 0 - 0)" ou "1ère mi-temps a commencé" si
    // Sofascore les utilise ailleurs (foot à un autre niveau).
    // PARTAGÉ AVEC TOUS LES AUTRES SPORTS : ce même libellé "Match
    // commencé" est aussi celui observé en tennis et présumé pareil
    // ailleurs (voir doc de classe) — c'est sans conséquence ici puisque
    // parse() ne l'utilise que si AUCUNE ligne "quart-temps"/"set terminé"
    // n'est présente, donc uniquement avant la fin de la 1ère période.
    private val firstHalfStarted = Regex(
        """(?:1\w*\s*mi-temps|match)(?:\s+a)?\s+commenc[ée](?:\s*:\s*(\d+)\s*-\s*(\d+))?""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Gabarit générique "MM' <libellé> : score - score" — couvre "But"
     * (confirmé) et, par construction, tout futur libellé horodaté par une
     * minute (carton, but annulé/corrigé après VAR...) SANS avoir besoin de
     * connaître le mot exact : quel que soit l'événement, les deux nombres
     * après les deux-points sont déjà le score à jour calculé par Sofascore,
     * donc pas besoin de les recalculer nous-mêmes (ex. une correction de
     * score après un but annulé donnera directement le bon score ici).
     */
    private val timedEvent = Regex(
        """(\d{1,3})'\s*[^:]+?\s*:\s*(?:\[(\d+)\]|(\d+))\s*-\s*(?:\[(\d+)\]|(\d+))""",
        RegexOption.IGNORE_CASE
    )

    // CONFIRMÉ par un exemple réel (15/09/2026, Kasuka FC - Karketu Díli,
    // une ligue moins couverte par Sofascore) : pas de minute du tout sur
    // certaines lignes de but ("But: [2] - 0 Kasuka FC", le nom qui suit
    // étant alors celui de l'ÉQUIPE plutôt que d'un joueur — sans
    // conséquence ici, ce nom n'est de toute façon pas exploité, ni ici ni
    // dans [timedEvent], seuls les deux scores comptent). Sans minute, pas
    // de "MM+" possible : statut vide plutôt que d'en inventer une — voir
    // wear/ScoreComplicationService.kt, qui omet alors le "· " devant le
    // score plutôt que d'afficher un statut vide ou trompeur.
    private val goalNoMinute = Regex(
        """But\s*:\s*(?:\[(\d+)\]|(\d+))\s*-\s*(?:\[(\d+)\]|(\d+))""",
        RegexOption.IGNORE_CASE
    )

    // CONFIRMÉ par un exemple réel (17/09/2026, Aurillac - Brive, capture fournie par Yann) :
    // rugby — même gabarit que [goalNoMinute] (pas de minute, score déjà à jour, crochet sur le
    // côté qui vient de marquer), juste avec le libellé "Score" plutôt que "But" — voir la doc de
    // classe pour l'exemple détaillé. Distinct de [matchFinished] ("Match terminé : ..."), qui ne
    // matche pas ce gabarit grâce au mot "terminé" entre "Match" et ":".
    private val scoreEvent = Regex(
        """Score\s*:\s*(?:\[(\d+)\]|(\d+))\s*-\s*(?:\[(\d+)\]|(\d+))""",
        RegexOption.IGNORE_CASE
    )

    // CONFIRMÉ par un exemple réel (15/09/2026, Elche - Real Madrid) : un
    // but peut être invalidé après coup (VAR ?) — Sofascore pousse alors
    // une ligne "Correction du score : H - A" avec le score remis à jour,
    // potentiellement INFÉRIEUR à un but affiché juste avant dans la même
    // notif (voir doc de classe). Même gabarit que [goalNoMinute] (pas de
    // minute sur cette ligne non plus) : les deux nombres sont déjà le
    // score corrigé, rien à recalculer. Comme les autres événements de ce
    // fichier, elle n'est utilisée que si c'est la ligne reconnue la PLUS
    // RÉCENTE (la boucle de [parseFootball] s'arrête au premier match) —
    // une correction plus ancienne qu'un but plus récent non corrigé est
    // donc naturellement ignorée sans logique dédiée.
    private val correctionScore = Regex(
        """Correction du score\s*:\s*(?:\[(\d+)\]|(\d+))\s*-\s*(?:\[(\d+)\]|(\d+))""",
        RegexOption.IGNORE_CASE
    )

    // --- Prolongation / tirs au but (foot) ---

    // CONFIRMÉ par un exemple réel (20/09/2026, Brésil U20 (F) - États-Unis U20 (F)) :
    // "Match terminé : 6 - 5 (5 - 4) (AP)" — voir doc de classe. Brackets optionnels sur le couple
    // entre parenthèses par robustesse (aucun exemple réel n'en montre, mais rien n'empêche
    // Sofascore de le faire un jour, comme sur les lignes de séance elles-mêmes) ; le score final
    // "H - A" lui-même n'a jamais de crochet, comme pour [matchFinished].
    private val matchFinishedWithShootout = Regex(
        """Match termin[ée]\s*:\s*(\d+)\s*-\s*(\d+)\s*\(\s*(?:\[(\d+)\]|(\d+))\s*-\s*(?:\[(\d+)\]|(\d+))\s*\)\s*\(AP\)""",
        RegexOption.IGNORE_CASE
    )

    // CONFIRMÉ par un exemple réel (20/09/2026, Italie U20 (F) - Chine U20 (F)) : "Séance de tirs
    // au but : [5] - 4 Gabriella Langella" — voir doc de classe. Même gabarit que [goalNoMinute]/
    // [scoreEvent] (score de la séance déjà à jour, crochet sur le côté qui vient de tirer).
    private val shootoutTick = Regex(
        """S[ée]ance de tirs au but\s*:\s*(?:\[(\d+)\]|(\d+))\s*-\s*(?:\[(\d+)\]|(\d+))""",
        RegexOption.IGNORE_CASE
    )

    // CONFIRMÉ par un exemple réel (20/09/2026, Italie U20 (F) - Chine U20 (F)) : "Penalty raté :
    // 5 - [4]Chenyu Li" — un tir raté ne change pas le score de la séance mais reste posté avec le
    // même gabarit "H - A" (inchangé par rapport au tir précédent) — voir doc de classe.
    private val missedPenalty = Regex(
        """Penalty rat[ée]\s*:\s*(?:\[(\d+)\]|(\d+))\s*-\s*(?:\[(\d+)\]|(\d+))""",
        RegexOption.IGNORE_CASE
    )

    // CONFIRMÉ par un exemple réel (20/09/2026, Italie U20 (F) - Chine U20 (F)) : "En attente des
    // pénaltys : 1 - 1" — juste avant le premier tir de la séance, voir doc de classe. Pas de
    // crochet sur cette ligne (le score du match, pas encore celui d'une séance commencée) — même
    // statut interne "TAB" ("PEN" affiché) que [shootoutTick]/[missedPenalty], voir [parse].
    private val awaitingPenalties = Regex(
        """En attente des p[ée]naltys\s*:\s*(\d+)\s*-\s*(\d+)""",
        RegexOption.IGNORE_CASE
    )

    // CONFIRMÉ par un exemple réel (20/09/2026, Italie U20 (F) - Chine U20 (F)) : "1re prolongation
    // a commencé: 1 - 1" — "1re" (pas "1ère"), même gabarit que [firstHalfStarted]/
    // [secondHalfStarted] mais pour la prolongation. Voir doc de classe pour la séquence complète.
    private val extraTimeFirstStarted = Regex(
        """1\w*\s*prolongation a commenc[ée]\s*:\s*(\d+)\s*-\s*(\d+)""",
        RegexOption.IGNORE_CASE
    )

    // CONFIRMÉ par un exemple réel (20/09/2026, Italie U20 (F) - Chine U20 (F)) : "2de prolongation
    // a commencé: 1 - 1" — voir doc de classe.
    private val extraTimeSecondStarted = Regex(
        """2\w*\s*prolongation a commenc[ée]\s*:\s*(\d+)\s*-\s*(\d+)""",
        RegexOption.IGNORE_CASE
    )

    // CONFIRMÉ par un exemple réel (20/09/2026, Italie U20 (F) - Chine U20 (F)) : "Mi-temps des
    // prolongations : 1 - 1" — pause médiane de la prolongation, À NE PAS CONFONDRE avec [halfTime]
    // ("Mi-temps : H-A", sans "des prolongations") — voir doc de classe.
    private val extraTimeHalftime = Regex(
        """Mi-temps des prolongations\s*:\s*(\d+)\s*-\s*(\d+)""",
        RegexOption.IGNORE_CASE
    )

    // CONFIRMÉ par un exemple réel (20/09/2026, Italie U20 (F) - Chine U20 (F)) : "En attente des
    // prolongations : 1 - 1" — pause entre la fin du temps réglementaire et le début de la
    // prolongation, voir doc de classe. Pas de statut dédié demandé par Yann pour cette transition
    // précise (contrairement à ET1/MTP/ET2/PEN) — réutilise le statut générique "ET" ("Prolongation"
    // côté montre/widget, vocabulaire TheSportsDB existant).
    private val awaitingExtraTime = Regex(
        """En attente des prolongations\s*:\s*(\d+)\s*-\s*(\d+)""",
        RegexOption.IGNORE_CASE
    )

    // --- Basket ---

    // Détecte la famille "basket" — "quart-temps" n'apparaît dans aucun
    // autre gabarit de ce fichier, pas de risque de faux positif.
    private val quarterMarker = Regex("""quart-temps""", RegexOption.IGNORE_CASE)

    private val quarterStarted = Regex(
        """(\d+)\w*\s*quart-temps a commenc[ée]\s*:\s*(?:\[(\d+)\]|(\d+))\s*-\s*(?:\[(\d+)\]|(\d+))""",
        RegexOption.IGNORE_CASE
    )

    private val quarterEnded = Regex(
        """(\d+)\w*\s*quart-temps termin[ée]\s*:\s*(?:\[(\d+)\]|(\d+))\s*-\s*(?:\[(\d+)\]|(\d+))""",
        RegexOption.IGNORE_CASE
    )

    // Pause médiane (fin du 2e quart-temps) : libellé dédié "Mi-temps
    // terminé" plutôt que "2e quart-temps terminé" (confirmé par l'exemple
    // réel, voir doc de classe) — À NE PAS CONFONDRE avec [halfTime] côté
    // foot ("Mi-temps : H-A", SANS "terminé").
    private val halftimeEndedQuarters = Regex(
        """Mi-temps termin[ée]\s*:\s*(?:\[(\d+)\]|(\d+))\s*-\s*(?:\[(\d+)\]|(\d+))""",
        RegexOption.IGNORE_CASE
    )

    // --- Tennis / sports à set ---

    /**
     * "1er set terminé : 6 - [7] L. Samsonova" (tennis) ou "3e set terminé
     * : [2] - 1 Choi H." (tennis de table/volley) -> groupe 1 = numéro
     * d'ordre du set, groupes 2/3 = score du premier nombre (domicile,
     * bracketé ou non), groupes 4/5 = score du second nombre (extérieur),
     * groupe 6 = nom du vainqueur du set (reste de la ligne après le
     * score). Le MÊME gabarit sert à tous ces sports — voir [parse] pour
     * comment on les distingue (la somme des deux scores égale, ou non, le
     * numéro d'ordre du set).
     */
    private val setEnded = Regex(
        """(\d+)\w*\s*set termin[ée]\s*:\s*(?:\[(\d+)\]|(\d+))\s*-\s*(?:\[(\d+)\]|(\d+))\s+(.+)""",
        RegexOption.IGNORE_CASE
    )

    // Repli "finished" bare (sans score exploitable) pour le tennis/tennis
    // de table/volley, au cas — jamais observé jusqu'ici — où "Match
    // terminé" apparaîtrait sans les deux nombres attendus par
    // [matchFinished] (ex. formulation différente). Dans ce cas [parse] ne
    // peut pas déjà avoir intercepté la fin de match, donc [parseTennis]/
    // [parseSetTally] doivent encore savoir la détecter, quitte à
    // retomber sur le dernier score reconstruit plutôt qu'un score fiable.
    private val setSportFinished = Regex("""Match termin[ée]""", RegexOption.IGNORE_CASE)

    /**
     * "home"/"away" selon le côté entouré de crochets dans une ligne
     * d'événement (celui qui vient de marquer, ou qui vient de gagner le
     * dernier set) — null si aucun crochet des deux côtés (repli neutre,
     * jamais observé jusqu'ici mais possible si Sofascore change son
     * format un jour). [homeBracketGroup]/[awayBracketGroup] sont les
     * indices des groupes de capture BRACKETÉS (pas les groupes "plain")
     * du gabarit utilisé — un groupe non participant renvoie "" (jamais
     * null) via [kotlin.text.MatchResult.groupValues], d'où
     * [isNotBlank] plutôt qu'une comparaison à null. Demandé de nouveau
     * par Yann le 15/09/2026 (repris depuis les notifs Sofascore
     * elles-mêmes, jusque-là ignoré côté app) — voir
     * wear/MatchScore.kt/scoreText pour l'affichage entre crochets.
     */
    private fun bracketedSide(groups: List<String>, homeBracketGroup: Int, awayBracketGroup: Int): String? = when {
        groups[homeBracketGroup].isNotBlank() -> "home"
        groups[awayBracketGroup].isNotBlank() -> "away"
        else -> null
    }

    /**
     * @return un MatchResult avec un statut au vocabulaire déjà connu de
     * wear/MatchClock.kt — FT/HT/2H/1H (traduits en Fin/MT/P2/P1 côté
     * montre) en foot/handball, Q1..Q4/HT/FT en basket (traduits en
     * P1..P4/MT/Fin) ; live/completed en tennis (traduit en Fin côté
     * montre) ; "S<N>"/"Fin" en tennis de table/volley (déjà le format
     * affiché tel quel côté montre, voir [parseSetTally]) — pour ne RIEN
     * avoir à changer côté montre au niveau du parsing (la traduction en
     * français, elle, se fait dans MatchClock.kt). Null si aucune ligne
     * reconnue dans le sport détecté (ex. seul "Match commencé" est
     * présent, ou événement pas encore couvert) — dans ce cas,
     * SofascoreNotificationListenerService retombe sur un repli neutre qui
     * affiche le texte brut de la ligne la plus récente.
     */
    fun parse(homeTeam: String, awayTeam: String, lines: List<String>): MatchResult? {
        // Score final AVEC séance de tirs au but — DOIT être vérifié AVANT le [matchFinished]
        // générique juste en dessous, qui matcherait déjà le préfixe "H - A" de cette même ligne
        // sans capturer la partie tirs au but entre parenthèses ni le marqueur "(AP)". Voir la doc
        // de classe pour l'exemple réel confirmé.
        val finishedWithShootout = lines.firstNotNullOfOrNull { matchFinishedWithShootout.find(it.trim()) }
        if (finishedWithShootout != null) {
            val home = finishedWithShootout.groupValues[1]
            val away = finishedWithShootout.groupValues[2]
            val penHome = finishedWithShootout.groupValues[3].ifBlank { finishedWithShootout.groupValues[4] }
            val penAway = finishedWithShootout.groupValues[5].ifBlank { finishedWithShootout.groupValues[6] }
            val penHomeText = if (finishedWithShootout.groupValues[3].isNotBlank()) "[$penHome]" else penHome
            val penAwayText = if (finishedWithShootout.groupValues[5].isNotBlank()) "[$penAway]" else penAway
            return build(homeTeam, awayTeam, "$home ($penHomeText)", "$away ($penAwayText)", "AP")
        }

        // Score final toujours prioritaire dès qu'il est présent, peu
        // importe sa position dans la liste et peu importe le sport — voir
        // la doc de classe pour les deux exemples réels qui l'imposent
        // (volley : ordre inversé ; basket : dernière période jamais
        // reçue séparément).
        val finished = lines.firstNotNullOfOrNull { matchFinished.find(it.trim()) }
        if (finished != null) {
            val home = finished.groupValues[1].ifBlank { finished.groupValues[2] }
            val away = finished.groupValues[3].ifBlank { finished.groupValues[4] }
            return build(homeTeam, awayTeam, home, away, "FT")
        }

        // Séance de tirs au but EN COURS, ou attente d'icelle (match pas encore fini) — statut
        // interne "TAB", traduit en "PEN" côté montre/widget (voir MatchClock.kt/
        // SofascoreMatchPresentation.kt). Un seul passage sur [lines], plus récent au plus ancien,
        // pour que la ligne réellement la plus récente gagne entre les deux gabarits possibles
        // ([shootoutTick]/[missedPenalty] pendant la séance, score de la séance déjà à jour, voir
        // doc de classe pour l'exemple réel et pourquoi le score du match avant la séance n'est en
        // général plus disponible à ce stade ; [awaitingPenalties] juste avant, score du match
        // encore intact).
        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            val tick = shootoutTick.find(trimmed) ?: missedPenalty.find(trimmed)
            if (tick != null) {
                val home = tick.groupValues[1].ifBlank { tick.groupValues[2] }
                val away = tick.groupValues[3].ifBlank { tick.groupValues[4] }
                return build(homeTeam, awayTeam, home, away, "TAB", lastScorer = bracketedSide(tick.groupValues, 1, 3))
            }
            awaitingPenalties.find(trimmed)?.let { m ->
                return build(homeTeam, awayTeam, m.groupValues[1], m.groupValues[2], "TAB")
            }
        }

        if (lines.any { quarterMarker.containsMatchIn(it) }) {
            return parseBasketball(homeTeam, awayTeam, lines)
        }

        val mostRecentSet = lines.firstNotNullOfOrNull { setEnded.find(it.trim()) }
        if (mostRecentSet != null) {
            val ordinal = mostRecentSet.groupValues[1].toIntOrNull()
            val firstScore = mostRecentSet.groupValues[2].ifBlank { mostRecentSet.groupValues[3] }.toIntOrNull()
            val secondScore = mostRecentSet.groupValues[4].ifBlank { mostRecentSet.groupValues[5] }.toIntOrNull()
            val lastSetWinner = bracketedSide(mostRecentSet.groupValues, 2, 4)

            // Voir la doc de classe : cette égalité ne peut être vraie que
            // si les deux scores sont un décompte CUMULATIF de sets
            // (tennis de table, volley), pas des jeux/points du set qui
            // vient de finir (tennis).
            if (ordinal != null && firstScore != null && secondScore != null && firstScore + secondScore == ordinal) {
                return parseSetTally(homeTeam, awayTeam, lines, ordinal, firstScore, secondScore, lastSetWinner)
            }
            return parseTennis(homeTeam, awayTeam, lines, lastSetWinner)
        }
        return parseFootball(homeTeam, awayTeam, lines)
    }

    private fun parseFootball(homeTeam: String, awayTeam: String, lines: List<String>): MatchResult? {
        for (i in lines.indices) {
            val line = lines[i].trim()

            // "Match terminé" n'est PAS vérifié ici : [parse] l'intercepte
            // déjà avant d'arriver dans cette fonction, pour tous les
            // sports (voir sa doc).
            halfTime.find(line)?.let { m ->
                return build(homeTeam, awayTeam, m.groupValues[1], m.groupValues[2], "HT")
            }
            secondHalfStarted.find(line)?.let { m ->
                return build(homeTeam, awayTeam, m.groupValues[1], m.groupValues[2], "2H")
            }
            firstHalfStarted.find(line)?.let { m ->
                val home = m.groupValues[1].ifBlank { "0" }
                val away = m.groupValues[2].ifBlank { "0" }
                return build(homeTeam, awayTeam, home, away, "1H")
            }
            // Prolongation — voir doc de classe pour l'exemple réel confirmé (Italie U20 (F) -
            // Chine U20 (F), 20/09/2026) et la séquence complète.
            awaitingExtraTime.find(line)?.let { m ->
                return build(homeTeam, awayTeam, m.groupValues[1], m.groupValues[2], "ET")
            }
            extraTimeFirstStarted.find(line)?.let { m ->
                return build(homeTeam, awayTeam, m.groupValues[1], m.groupValues[2], "ET1")
            }
            extraTimeHalftime.find(line)?.let { m ->
                return build(homeTeam, awayTeam, m.groupValues[1], m.groupValues[2], "MTP")
            }
            extraTimeSecondStarted.find(line)?.let { m ->
                return build(homeTeam, awayTeam, m.groupValues[1], m.groupValues[2], "ET2")
            }
            timedEvent.find(line)?.let { m ->
                val minute = m.groupValues[1]
                val home = m.groupValues[2].ifBlank { m.groupValues[3] }
                val away = m.groupValues[4].ifBlank { m.groupValues[5] }
                // "37+" (demandé par Yann le 15/09/2026), pas juste la
                // minute nue : évite de passer par la règle générique de
                // wear/MatchClock.kt ("$status'", pensée pour un tout
                // autre cas — une minute brute parfois renvoyée par
                // TheSportsDB, sans rapport avec un but) et affiche
                // directement le format voulu via sa branche `else`.
                return build(homeTeam, awayTeam, home, away, "$minute+", lastScorer = bracketedSide(m.groupValues, 2, 4))
            }
            correctionScore.find(line)?.let { m ->
                val home = m.groupValues[1].ifBlank { m.groupValues[2] }
                val away = m.groupValues[3].ifBlank { m.groupValues[4] }
                return build(homeTeam, awayTeam, home, away, "", lastScorer = bracketedSide(m.groupValues, 1, 3))
            }
            goalNoMinute.find(line)?.let { m ->
                val home = m.groupValues[1].ifBlank { m.groupValues[2] }
                val away = m.groupValues[3].ifBlank { m.groupValues[4] }
                return build(homeTeam, awayTeam, home, away, "", lastScorer = bracketedSide(m.groupValues, 1, 3))
            }
            scoreEvent.find(line)?.let { m ->
                val home = m.groupValues[1].ifBlank { m.groupValues[2] }
                val away = m.groupValues[3].ifBlank { m.groupValues[4] }
                // Rugby (pas de minute sur cette ligne, contrairement à [timedEvent]) — voir doc de
                // classe : détermine 1re/2e période à partir des marqueurs de mi-temps déjà connus.
                val period = currentHalfStatus(lines, i)
                return build(homeTeam, awayTeam, home, away, period, lastScorer = bracketedSide(m.groupValues, 1, 3))
            }
        }
        return null
    }

    /**
     * Période (1re/2e mi-temps) en cours à la ligne [fromIndex] (une ligne [scoreEvent] — rugby,
     * sans minute) : cherche, parmi les lignes plus ANCIENNES (index >= [fromIndex], [lines] étant
     * triée du plus récent au plus ancien, voir la doc de [SofascoreNotificationListenerService.collectLines]),
     * le premier marqueur de période déjà reconnu par ce fichier. "2de mi-temps a commencé" trouvé
     * avant tout le reste -> 2e période déjà en cours ("2H", traduit en "P2" côté montre/widget) ;
     * "Mi-temps"/"Match commencé" (ou rien trouvé jusqu'à la fin de la liste) -> encore en 1re
     * période ("1H", traduit en "P1"). AJOUTÉ le 20/09/2026 à la demande de Yann ("indiquer la
     * période 1 ou 2 au rugby") — voir doc de classe pour l'exemple réel (Vannes - Toulouse) qui
     * montre pourquoi c'est nécessaire : la ligne [scoreEvent] la plus récente ne porte, à elle
     * seule, aucune indication de période.
     */
    private fun currentHalfStatus(lines: List<String>, fromIndex: Int): String {
        for (j in fromIndex until lines.size) {
            val line = lines[j].trim()
            if (secondHalfStarted.containsMatchIn(line)) return "2H"
            if (halfTime.containsMatchIn(line) || firstHalfStarted.containsMatchIn(line)) return "1H"
        }
        return "1H"
    }

    /**
     * Basket — voir doc de classe pour l'exemple réel. Déduit le statut de
     * la DERNIÈRE période reconnue :
     * - "Xe quart-temps a commencé" -> "Q<X>" directement (le plus fiable :
     *   le quart-temps X est explicitement en cours)
     * - "Mi-temps terminé" -> "HT"
     * - "Xe quart-temps terminé" -> "Q<X+1>" (pas de ligne "a commencé"
     *   distincte pour le quart-temps suivant dans l'exemple réel, donc
     *   déduit comme pour le set suivant en volley/tennis de table — voir
     *   [parseSetTally]) ; le score reste celui de fin de quart-temps X
     *   (inchangé jusqu'au prochain panier)
     * "Match terminé" n'est PAS vérifié ici : [parse] l'intercepte déjà
     * avant d'arriver dans cette fonction (voir sa doc — nécessaire
     * puisque la toute dernière période n'est parfois jamais reçue
     * séparément, contrairement à ici où on reconstruit à partir d'une
     * période forcément déjà connue). Pas de crochets sur ces lignes dans
     * l'exemple réel (contrairement au foot/volley/tennis) : le résultat
     * ne renseigne donc jamais [MatchResult.lastScorer].
     */
    private fun parseBasketball(homeTeam: String, awayTeam: String, lines: List<String>): MatchResult? {
        for (raw in lines) {
            val line = raw.trim()

            quarterStarted.find(line)?.let { m ->
                val home = m.groupValues[2].ifBlank { m.groupValues[3] }
                val away = m.groupValues[4].ifBlank { m.groupValues[5] }
                return build(homeTeam, awayTeam, home, away, "Q${m.groupValues[1]}")
            }
            halftimeEndedQuarters.find(line)?.let { m ->
                val home = m.groupValues[1].ifBlank { m.groupValues[2] }
                val away = m.groupValues[3].ifBlank { m.groupValues[4] }
                return build(homeTeam, awayTeam, home, away, "HT")
            }
            quarterEnded.find(line)?.let { m ->
                val ordinal = m.groupValues[1].toInt()
                val home = m.groupValues[2].ifBlank { m.groupValues[3] }
                val away = m.groupValues[4].ifBlank { m.groupValues[5] }
                return build(homeTeam, awayTeam, home, away, "Q${ordinal + 1}")
            }
        }
        return null
    }

    /**
     * Compte les sets gagnés par chaque joueur en additionnant TOUTES les
     * lignes [setEnded] présentes (pas juste la plus récente, contrairement
     * au tennis de table/volley) : chaque ligne de fin de set nomme son
     * vainqueur, on incrémente son compteur en comparant ce nom à
     * [homeTeam]/[awayTeam] (comparaison exacte, insensible à la casse —
     * les deux viennent de la même notif Sofascore donc au même format
     * "Initiale. Nom", pas de transformation nécessaire). [lastSetWinner]
     * (déjà calculé par [parse] à partir de la ligne [setEnded] la plus
     * RÉCENTE, par crochet plutôt que par nom — voir [bracketedSide])
     * renseigne qui a gagné le DERNIER set, pas le décompte total. "Match
     * terminé" n'est pas vérifié ici : [parse] l'intercepte déjà avant
     * d'arriver dans cette fonction ; [setSportFinished] ne reste utile
     * qu'en repli si cette ligne existait sans score exploitable (voir sa
     * doc). Pas de score de jeux du set en cours (Sofascore ne le donne pas
     * en direct dans ces notifs).
     *
     * Statut "S<N>"/"Fin" (20/09/2026, Yann : "mets les sets comme les autres sports") — même
     * gabarit que [parseSetTally], traité par la branche générique de wear/MatchClock.kt#label /
     * SofascoreMatchPresentation.kt#periodLabel.
     */
    private fun parseTennis(
        homeTeam: String,
        awayTeam: String,
        lines: List<String>,
        lastSetWinner: String?
    ): MatchResult {
        var homeSets = 0
        var awaySets = 0
        for (raw in lines) {
            val line = raw.trim()
            setEnded.find(line)?.let { m ->
                when (m.groupValues[6].trim()) {
                    homeTeam -> homeSets++
                    awayTeam -> awaySets++
                    else -> Unit // nom du vainqueur inattendu (format différent ?) — set ignoré du décompte plutôt que de deviner
                }
            }
        }
        val finished = lines.any { setSportFinished.containsMatchIn(it) }
        val status = if (finished) "Fin" else "S${homeSets + awaySets + 1}"
        return MatchResult(
            homeTeam = homeTeam,
            awayTeam = awayTeam,
            homeScore = homeSets.toString(),
            awayScore = awaySets.toString(),
            lastScorer = lastSetWinner,
            status = status
        )
    }

    /**
     * Tennis de table, et volley (confirmé, voir doc de classe) : la ligne
     * "Xe set terminé" la plus récente donne DÉJÀ le score total à jour
     * (nombre de sets gagnés par chacun) — pas besoin d'additionner
     * plusieurs lignes comme au tennis, [mostRecentSetOrdinal]/
     * [firstScore]/[secondScore]/[lastSetWinner] viennent directement de
     * [parse]. Statut "S<N>" (N = numéro du set en cours = dernier set
     * terminé + 1) tant que le match n'est pas fini, "Fin" sinon (repli
     * [setSportFinished], voir sa doc — "Match terminé" est en pratique
     * déjà intercepté plus haut par [parse]) — "S<N>" est déjà le format
     * utilisé tel quel par TheSportsDB pour le volley (`S1`..`S5`, voir
     * wear/MatchClock.kt), affiché sans traduction dédiée côté montre :
     * cohérent par construction, sans l'avoir cherché.
     */
    private fun parseSetTally(
        homeTeam: String,
        awayTeam: String,
        lines: List<String>,
        mostRecentSetOrdinal: Int,
        firstScore: Int,
        secondScore: Int,
        lastSetWinner: String?
    ): MatchResult {
        val finished = lines.any { setSportFinished.containsMatchIn(it) }
        val status = if (finished) "Fin" else "S${mostRecentSetOrdinal + 1}"
        return MatchResult(
            homeTeam = homeTeam,
            awayTeam = awayTeam,
            homeScore = firstScore.toString(),
            awayScore = secondScore.toString(),
            lastScorer = lastSetWinner,
            status = status
        )
    }

    private fun build(
        homeTeam: String,
        awayTeam: String,
        homeScore: String,
        awayScore: String,
        status: String,
        lastScorer: String? = null
    ) = MatchResult(
        homeTeam = homeTeam,
        awayTeam = awayTeam,
        homeScore = homeScore,
        awayScore = awayScore,
        lastScorer = lastScorer,
        status = status
    )
}
