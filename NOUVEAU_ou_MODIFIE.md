# Widget Now Bar — actions de la notification dans le widget

NOUVEAU (à créer) :
- app/src/main/java/com/yann/nowbarmirror/Settings/WidgetActionsPrefs.kt

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
sous le titre/texte avec jusqu'à deux boutons d'action tirés de la notification d'origine
(ex. "Répondre", "Marquer comme lu"), alignés sous le texte plutôt que sous l'icône de
l'appli.

Comme pour le tap d'ouverture du widget, les PendingIntent des actions ne peuvent pas être
sauvegardés dans les préférences — ils ne survivent qu'à une vraie transaction Binder. Ils
sont donc gardés en mémoire, valables tant que le process de l'appli reste vivant depuis la
réception de la notif. Après un redémarrage du téléphone ou un kill du process par le
système, les boutons restent cachés jusqu'à la prochaine notif reçue — même limite que le
tap d'ouverture, déjà documentée dans le code.

Le widget garde exactement le même rendu qu'avant pour qui n'active pas l'option : la ligne
d'actions est en `visibility="gone"` par défaut dans le layout.

Si tu actives l'option, il faudra probablement agrandir un peu la hauteur du widget dans
LockStar pour laisser de la place à la deuxième ligne.

Le Toast temporaire de diagnostic sur le widget est toujours là si jamais une erreur survient.
