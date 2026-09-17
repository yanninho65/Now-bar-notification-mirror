# Vue "Sport" dans le widget écran de verrouillage (17/09/2026)

## Décision
Ajout d'une deuxième vue au widget lock-screen (`NowBarWidgetProvider`),
basculée par un bouton flèches-qui-tournent sous l'icône à gauche : la vue
habituelle "dernière notif" (inchangée) et une nouvelle vue "Sport" montrant
jusqu'à 4 matchs Sofascore, avec la même présentation image/score/période
que la complication montre `SMALL_IMAGE`, adaptée aux proportions du
téléphone.

## Fichiers
NOUVEAU (à créer) :
- app/src/main/res/drawable/ic_widget_switch_view.xml
- app/src/main/res/drawable/bg_sofascore_placeholder.xml
- app/src/main/java/com/yann/nowbarmirror/widget/WidgetViewModePrefs.kt
- app/src/main/java/com/yann/nowbarmirror/widget/SofascoreWidgetStore.kt
- app/src/main/java/com/yann/nowbarmirror/widget/SofascoreMatchPresentation.kt

MODIFIÉ (à remplacer en entier) :
- app/src/main/res/layout/widget_now_bar.xml
- app/src/main/java/com/yann/nowbarmirror/widget/NowBarWidgetProvider.kt
- app/src/main/java/com/yann/nowbarmirror/sport/SofascoreNotificationListenerService.kt
- app/src/main/res/values/strings.xml

## Choix structurants

- **Colonne de gauche partagée entre les deux vues** : `widget_app_icon`
  (icône de l'appli mirrorée, ou icône Sofascore selon la vue) reste au
  même endroit, avec les flèches (`widget_view_toggle`) juste en dessous.
  Le layout est passé d'un `LinearLayout` vertical à une racine horizontale
  (colonne de gauche + zone de contenu) — la zone de contenu est un
  `FrameLayout` qui superpose l'ancienne vue (`widget_latest_content`) et
  la nouvelle (`widget_sofascore_content`), une seule visible à la fois.
  Conséquence mineure : le tap "ouvrir la notif" n'est plus lié à tout le
  widget mais spécifiquement à `widget_latest_content` — taper sur l'icône
  elle-même n'ouvre plus rien (elle sert maintenant aussi à afficher
  l'icône Sofascore, donc son tap ne pouvait plus avoir un seul sens fixe).

- **Flèches visibles seulement quand pertinent** : dans la vue "dernière
  notif", les flèches n'apparaissent que s'il y a au moins un match
  Sofascore actif à montrer — sinon le widget reste pixel-identique à
  avant cet ajout. Dans la vue Sport, elles restent toujours visibles pour
  pouvoir revenir en arrière, même si les matchs affichés tombent à zéro
  entre-temps (auquel cas un texte "Aucun match" apparaît à la place des
  vignettes).

- **4 emplacements fixes** (`widget_match_1`..`4`), pas une liste
  dynamique — même approche que les 3 boutons d'action déjà dans ce
  layout (`widget_action_1`..`3`), plus simple qu'une collection
  RemoteViews pour un plafond fixe.

- **Tri : priorité aux matchs en cours** — un match dont le statut est
  "terminé" (`SofascoreMatchPresentation.isMatchFinished`) ET dont la
  dernière notif date de plus de 5 minutes passe après les autres ; à
  l'intérieur de chaque groupe, tri par notif la plus récente. Fait à
  l'envoi (pour choisir quels 4 matchs garder) ET à nouveau à chaque
  rendu du widget (pour que l'ordre RELATIF des 4 déjà gardés reste juste
  au fil du temps, même sans nouvel événement Sofascore) — voir
  `NowBarWidgetProvider.sortedForWidget`.

- **Score/période SANS l'override API** : le système d'override
  (TheSportsDB/Live Tennis, voir `SofascoreApiOverridePrefs`) ne suit
  qu'UN SEUL match (celui actif pour la montre) et n'est pas construit
  pour en suivre 4 à la fois. Le widget affiche donc toujours le score/la
  période tels que la notif Sofascore elle-même les donne — comme le fait
  déjà aujourd'hui tout match qui n'est pas le match actif de la montre.

- **Image NON recadrée en cercle** dans la vue Sport (contrairement à
  `widget_image` dans la vue habituelle) : c'est l'image combinée des deux
  logos telle que Sofascore la compose déjà (voir
  `NotificationImageExtractor`), généralement deux logos côte à côte — un
  recadrage circulaire en couperait un des deux. `ImageView` avec
  `scaleType="fitCenter"` + `adjustViewBounds` à la place (le rendu
  natif Android gère la conservation du ratio, pas besoin de recomposer
  un bitmap à la main comme le fait `wear/ComplicationImageComposer.kt`
  pour la montre).

- **Tap sur un match = ouvrir Sofascore SANS effacer la notif source** :
  chaque vignette utilise le `PendingIntent` propre à cette notif
  (`contentIntent`, exactement celui que Sofascore lui-même a posé),
  jamais une action de suppression — contrairement à la vue habituelle,
  la vue Sport n'a volontairement aucun bouton de suppression par match.

- **Persistance** : `SofascoreWidgetStore` suit le même principe que
  `WidgetNotificationStore` (JSON en `SharedPreferences` pour les champs,
  PNG sur disque pour les images), étendu à une petite liste ordonnée au
  lieu d'une seule entrée. `WidgetViewModePrefs` retient juste quelle vue
  est actuellement affichée (un seul widget placé par Yann, donc une
  seule valeur partagée suffit).

- **Bascule de vue** : `NowBarWidgetProvider` intercepte maintenant une
  action `ACTION_TOGGLE_VIEW` dans son propre `onReceive` (avant de
  déléguer au comportement standard d'`AppWidgetProvider`) — même
  principe que la gestion d'`ACTION_DISMISS_WIDGET` déjà présente dans
  `MirrorNotificationListener.onStartCommand`.

- **`SofascoreMatchPresentation`** reprend la logique de
  `wear/MatchScore.kt` (`scoreText`, crochets) et `wear/MatchClock.kt`
  (`label`, vocabulaire P1/MT/Fin/etc.) côté téléphone — le module `:wear`
  n'est pas accessible depuis `:app`, donc petite duplication assumée
  plutôt qu'un module partagé. Une seule simplification : en tennis "en
  direct", pas de détail set-par-set ("3e set 4-3") comme sur la montre —
  juste "En direct", faute de place dans une vignette aussi compacte.

## À vérifier sur l'appareil
- Les proportions des vignettes (52dp de large, image 20dp, score 11sp,
  période 8sp) sont un premier réglage à l'œil, pas mesuré sur le S26 —
  dis-moi si c'est trop petit/trop serré une fois affiché en vrai
  (notamment si le widget est placé sur une largeur plus étroite que
  prévu via LockStar).
- La colonne de gauche est plus haute qu'avant quand les flèches sont
  visibles (icône 32dp + flèches 20dp empilées) — à confirmer que ça
  reste bien centré/pas coupé selon la hauteur que LockStar alloue au
  widget.
- L'icône de bascule (`ic_widget_switch_view.xml`) est un dessin fait
  maison, pas repris d'un jeu d'icônes — dis-moi si tu préfères un autre
  style, c'est un simple fichier vecteur à remplacer.

## Livraison
Zip (`widget-sofascore-view.zip`) reproduisant l'arborescence des dossiers
depuis la racine du dépôt, à merger dans
`Now-bar-notification-mirror`. Compilation réelle via ton GitHub Actions
existant (`.github/workflows/build.yml`) — aucun SDK Android disponible
ici pour compiler/vérifier localement ; les fichiers Kotlin/XML ont été
relus et vérifiés bien formés (XML) et équilibrés (accolades/parenthèses),
et tous les `R.id`/`R.drawable`/`R.string` référencés existent bien — mais
pas de compilation Gradle réelle de mon côté.
