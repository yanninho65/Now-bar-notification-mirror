# Widget Now Bar — actions de la notification dans le widget (texte, jusqu'à 3)

NOUVEAU (à créer) :
- app/src/main/java/com/yann/nowbarmirror/Settings/WidgetActionsPrefs.kt
- app/src/main/res/drawable/widget_action_background.xml

MODIFIÉ (à remplacer en entier) :
- app/src/main/java/com/yann/nowbarmirror/widget/NowBarWidgetProvider.kt
- app/src/main/java/com/yann/nowbarmirror/MirrorNotificationListener.kt
- app/src/main/res/layout/widget_now_bar.xml
- app/src/main/res/layout/activity_app_selection.xml
- app/src/main/res/values/mirror_mode_strings.xml
- app/src/main/java/com/yann/nowbarmirror/Settings/AppSelectionActivity.kt
- app/src/main/java/com/yann/nowbarmirror/Settings/SettingsBackup.kt

## Ce qui a changé

Nouveau réglage "Actions dans le widget" dans l'écran de réglages (désactivé par défaut,
inclus dans l'export/import JSON). Une fois activé, le widget affiche une deuxième ligne
sous le titre/texte avec jusqu'à trois boutons — même plafond que le mirroir système —
tirés de la notification d'origine, en TEXTE (le libellé de l'action, ex. "Répondre",
"Marquer comme lu") plutôt qu'en icône, sur un fond arrondi semi-transparent. Une action
sans libellé exploitable est simplement ignorée plutôt qu'affichée comme un bouton vide.

Comme pour le tap d'ouverture du widget, les PendingIntent des actions ne peuvent pas être
sauvegardés dans les préférences — ils ne survivent qu'à une vraie transaction Binder. Ils
sont donc gardés en mémoire, valables tant que le process de l'appli reste vivant depuis la
réception de la notif. Après un redémarrage du téléphone ou un kill du process par le
système, les boutons restent cachés jusqu'à la prochaine notif reçue — même limite que le
tap d'ouverture, déjà documentée dans le code.

Le widget garde exactement le même rendu qu'avant pour qui n'active pas l'option : la ligne
d'actions est en `visibility="gone"` par défaut dans le layout.

Si tu actives l'option, il faudra probablement agrandir un peu la hauteur du widget dans
LockStar pour laisser de la place à la deuxième ligne — avec 3 boutons texte plutôt qu'un
seul mot ("Répondre", "Archiver", "Marquer comme lu"), vérifie que ça tient bien en largeur
sur ton placement ; sinon le dernier bouton peut être coupé (pas de retour à la ligne prévu).

Le Toast temporaire de diagnostic sur le widget est toujours là si jamais une erreur survient.
