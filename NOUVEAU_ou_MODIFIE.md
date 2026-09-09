# Style One UI + icône Now Bar

NOUVEAU (à créer) :
- app/src/main/res/values-night/colors.xml
- app/src/main/res/color/toggle_button_bg.xml
- app/src/main/res/color/toggle_button_stroke.xml
- app/src/main/res/color/toggle_button_text.xml
- app/src/main/res/drawable/bg_card.xml
- app/src/main/res/drawable/bg_app_row_active.xml
- app/src/main/res/drawable/bg_app_row_inactive.xml
- app/src/main/res/drawable/ic_launcher_background.xml
- app/src/main/res/drawable/ic_launcher_foreground.xml
- app/src/main/res/drawable/ic_launcher_monochrome.xml
- app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
- app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
- app/src/main/res/layout/activity_main.xml

MODIFIÉ (à remplacer en entier) :
- app/build.gradle.kts
- app/src/main/AndroidManifest.xml
- app/src/main/res/values/colors.xml
- app/src/main/res/values/themes.xml
- app/src/main/res/values/strings.xml
- app/src/main/res/values/mirror_mode_strings.xml
- app/src/main/res/layout/activity_app_selection.xml
- app/src/main/res/layout/item_selectable_app.xml
- app/src/main/java/com/yann/nowbarmirror/MainActivity.kt
- app/src/main/java/com/yann/nowbarmirror/Settings/AppSelectionActivity.kt
- app/src/main/java/com/yann/nowbarmirror/Settings/AppSelectionAdapter.kt
- README.md

## Ce qui a changé

Passage du thème de `Theme.AppCompat.DayNight.NoActionBar` à
`Theme.MaterialComponents.DayNight.NoActionBar` (ajout de la dépendance
`com.google.android.material:material`), avec une palette "One UI" définie dans
`colors.xml` / `values-night/colors.xml` (fond gris clair/gris foncé, cartes
blanches/gris foncé, bleu d'accent, textes primaire/secondaire).

**Écran principal** : passe d'un layout 100% programmatique (Kotlin) à un XML
(`activity_main.xml`) avec un grand titre, une description, puis les trois
actions ("Autoriser l'accès aux notifications", "Autoriser les notifications
de l'app", "Applications à mirrorer") regroupées dans une carte arrondie avec
séparateurs, comme les listes de réglages One UI.

**Écran de sélection des apps** : les trois interrupteurs (service actif,
fallback, actions widget) et les boutons export/import sont maintenant dans
une carte arrondie. Les `Switch` deviennent des `SwitchMaterial` teintés en
bleu. Un grand titre "Applications à mirrorer" apparaît en haut (il n'y avait
aucun titre visible avant, le thème étant NoActionBar).

**Liste des apps — le point demandé** : le `Spinner` de mode est remplacé par
un `MaterialButtonToggleGroup` de 3 boutons ("Aucun" / "Dernière" / "Toutes").
Le bouton actif est rempli en bleu avec texte blanc, les deux autres restent
en contour gris avec texte gris — donc l'état sélectionné saute aux yeux
directement dans la liste, sans avoir à ouvrir un menu déroulant. En plus,
toute la ligne d'une app active (mode ≠ Aucun) prend un fond légèrement teinté
en bleu, et une app sur "Aucun" reste sur fond neutre avec l'icône et le nom
légèrement grisés (alpha 0.6) — double signal pour repérer d'un coup d'œil
quelles apps sont mirrorées.

**Icône de l'app** : nouvelle icône adaptative (fond bleu uni + pilule sombre
avec un petit rond et deux barres, comme une notification Live Activity dans
la Now Bar), plus une couche monochrome pour les icônes thématisées (Android
13+ / One UI). Pas besoin de mipmaps raster : `minSdk 26` couvre déjà les
icônes adaptatives, donc tout est en `mipmap-anydpi-v26/` avec des vecteurs.

`ic_stat_mirror.xml` n'a pas été touché : il ne sert plus qu'au widget (icône
de repli quand il n'y a pas de notification), en dehors du périmètre demandé.

Tous les fichiers XML ont été validés (bien formés), et tous les `R.id.` /
`@string` / `@color` / `@drawable` / `R.array.` référencés dans le Kotlin et
les layouts ont été vérifiés comme existants — mais je n'ai pas pu compiler
avec le SDK Android complet, donc un premier build via GitHub Actions reste
la vraie validation.

## README.md

Le README n'avait pas suivi les dernières fonctionnalités : il documentait
déjà le mode par app (Aucun/Dernière/Toutes), l'export/import et le switch
"Service actif", mais pas le widget écran de verrouillage (`NowBarWidgetProvider`,
placement via LockStar), les "Actions dans le widget", le fallback "Revenir à
la précédente après suppression", ni le "Titre ↔ texte" par app — ces
fonctionnalités existaient déjà dans le code mais n'étaient nulle part
décrites. Mis à jour : section "What it does" complétée, nouvelle section
"Lock-screen widget", nouvelle section "Style" (le sujet de cette série de
changements), arborescence "Project layout" complétée (`LatestModePrefs.kt`,
`WidgetActionsPrefs.kt`, le dossier `widget/`), section "Build" précisée avec
les étapes concrètes pour récupérer l'APK depuis l'onglet Actions sur
téléphone, et "Recommended first test" complété avec le widget et l'inversion
titre/texte.
