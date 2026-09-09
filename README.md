# Now Bar Mirror

Android app that mirrors selected apps' notifications into persistent notification(s) intended to be eligible for Samsung One UI / Android Live Update surfaces, plus a lock-screen widget showing the latest one.

## What it does

- Listens to notifications using `NotificationListenerService`.
- Ignores its own notifications, ongoing notifications, and group-summary bundles (e.g. WhatsApp's "X new messages").
- Only mirrors apps you've explicitly selected — nothing is mirrored by default.
- Per app, you choose one of two modes:
  - **Dernière notif (LATEST)** — all apps in this mode share a single mirror slot; whichever posts most recently occupies it, replacing whatever was shown before.
  - **Toutes (ALL)** — every distinct notification from this app gets its own persistent mirror, shown at the same time.

  Example: Signal and WhatsApp set to "Toutes", Le Monde and Mediapart set to "Dernière notif". Two WhatsApp messages + two Signal messages + one Mediapart notification all show at once (five mirrors). A Le Monde notification arriving next replaces the Mediapart one, since both share the LATEST slot.
- Per app, an independent "Titre ↔ texte" checkbox swaps which field is treated as the title — useful when an app's "text" field is actually the more relevant short summary for compact surfaces like the widget.
- Copies title and text.
- Uses the notification's large icon/contact image when available, otherwise the source app icon.
- Copies up to three standard action buttons and reuses their original `PendingIntent`.
- If an original notification is removed, its mirror is removed.
- If a mirror is removed by the user (swipe, or "clear all"), the original notification is cancelled through the notification-listener API. Removing our own mirror to replace its content (LATEST-mode swap, ALL-mode update) does **not** trigger this — only a genuine user dismissal does.
- "Revenir à la précédente après suppression" (on by default) — when the shared "Dernière notif" slot is dismissed while other LATEST-mode originals are still active elsewhere, the slot is re-posted with the next most recent survivor instead of just being cleared.
- A "Service actif" switch lets you pause mirroring entirely without uninstalling or revoking notification access; existing delete-sync keeps working for mirrors already showing while paused.
- Settings (per-app modes, invert flags, service on/off, widget actions, fallback) can be exported to / imported from a JSON file via the system file picker.
- On Android 16+, requests a promoted ongoing notification so the system can consider it for Live Update surfaces.
- Includes the Samsung ongoing-activity application metadata used by current One UI implementations.

## Lock-screen widget

A 4x1, background-less widget (`NowBarWidgetProvider`) mirrors whichever selected app posted most recently — across ALL- and LATEST-mode apps together, unlike the system mirror slots which keep them separate. It shows the source app's icon, title/text, the notification's image if any, and a dismiss button; tapping it opens the original notification, or launches the source app if the live `PendingIntent` isn't available anymore (e.g. after a process restart).

It's meant to sit directly on the lock screen over the wallpaper (no card background), placed there through a third-party lock-widget host such as Samsung's LockStar — it can also be added like any normal widget from the home-screen widget picker.

"Actions dans le widget" (off by default) adds up to three of the notification's own text action buttons (e.g. "Répondre", "Marquer comme lu") in a second row under the title/text — only while this app's process has stayed alive since that exact notification arrived, since a `PendingIntent` can't be reconstructed from storage after a process restart (same limitation as the tap-to-open).

## Choosing which apps to mirror

Open **Now Bar Mirror** → **Applications à mirrorer**. Each installed app with a launcher icon is listed with a segmented mode selector (Aucun / Dernière / Toutes) and the invert-title/text checkbox. The same screen has the service on/off switch, the "revenir à la précédente" fallback switch, the widget-actions switch, and the export/import buttons.

## Style

The app follows Samsung's One UI look: rounded cards grouping related settings, a bold large-title header on each screen, and a light/dark palette that follows the system theme. In the app list, each app's mode is a segmented Aucun/Dernière/Toutes control — the active mode is filled in blue, the others stay outlined — and the whole row is tinted when that app is actively mirrored, so it's obvious at a glance which apps are on. The launcher icon is a stylized rendition of the Now Bar itself: a dark capsule holding an avatar dot and two content lines, on a blue background, with a themed-icon layer for Android 13+ / One UI icon theming.

## Project layout

```
app/src/main/java/com/yann/nowbarmirror/
├── MainActivity.kt                    entry screen: permissions + link to app selection
├── MirrorNotificationListener.kt      the NotificationListenerService itself
├── settings/
│   ├── MirrorMode.kt                  NONE / LATEST / ALL
│   ├── AppMirrorPrefs.kt              per-package mode + invert-title/text storage
│   ├── ServicePrefs.kt                service on/off flag
│   ├── LatestModePrefs.kt             "revenir à la précédente" fallback flag
│   ├── WidgetActionsPrefs.kt          widget action-buttons on/off flag
│   ├── SettingsBackup.kt              JSON export/import of the above
│   ├── AppSelectionActivity.kt        the settings screen
│   └── AppSelectionAdapter.kt         RecyclerView adapter for the app list
└── widget/
    ├── NowBarWidgetProvider.kt        the 4x1 lock-screen widget
    └── WidgetNotificationStore.kt     persists the widget's current notification
```

## Important limitation

The Android notification listener API can observe and cancel notifications, and notification actions expose their `PendingIntent`s. Samsung ultimately controls whether and how an ongoing notification appears in the Now Bar. This project therefore deliberately uses the public Android notification APIs plus the Samsung ongoing-activity hint; it does not attempt to depend on undocumented Samsung framework internals.

Inline-reply `RemoteInput` actions are not yet reconstructed, either in the system-notification mirror or in the widget.

Both the widget's tap-to-open and its action buttons rely on a live `PendingIntent` held in memory since the notification was mirrored — after the app's process is killed and restarted they stop working (falling back to opening the source app, or hiding, respectively) until the next notification arrives. ALL-mode mirror tracking has the same in-memory-only limitation.

Since the app targets API 30+, it declares a `<queries>` entry for the `MAIN`/`LAUNCHER` intent in the manifest — without it, Android's package-visibility restrictions would hide every user-installed app (WhatsApp, Signal, etc.) from the app-selection screen, leaving only system apps visible.

## Build

The APK is built via the GitHub Actions workflow in this repo (`.github/workflows/build.yml`) rather than locally in Android Studio, since the day-to-day workflow here is phone-only:

1. Push to `main`, or trigger it manually from the **Actions** tab → "Build debug APK" → **Run workflow**.
2. Once the run finishes, open it and download the `NowBarMirror-debug-apk` artifact.
3. Unzip it on the phone and install `app-debug.apk`.
4. Open **Now Bar Mirror**, enable **Notification access**, allow the app's own notifications (Android 13+), and pick which apps to mirror.
5. To place the widget: add it from the home-screen widget picker, or through a lock-widget host like LockStar for the lock screen itself.

The debug signing key is generated once via the separate "Generate debug keystore" workflow and stored as the `DEBUG_KEYSTORE_B64` repo secret — no need to re-run it unless the secret is lost.

To build locally instead (Android Studio, JDK 17): open the folder, let Gradle sync, then `app` → `assembleDebug`.

## Recommended first test

Use Samsung Messages, WhatsApp, Signal or another app that produces a normal alert notification with a title, text, image and action buttons.

1. Set it to "Toutes" (or "Dernière notif") in the app-selection screen.
2. Confirm the mirror notification appears — and, if the widget is placed, that it updates too.
3. Confirm the source notification remains visible.
4. Send a second, different notification and confirm the previous behavior (replace for LATEST, new mirror for ALL) matches expectations.
5. Removing the source removes the mirror.
6. Removing the mirror removes the source.
7. Tapping an action performs the source app's action — in the system mirror, and in the widget too if "Actions dans le widget" is on.
8. Toggle "Service actif" off, confirm new notifications from selected apps stop mirroring, then toggle it back on.
9. Toggle "Titre ↔ texte" for one app and confirm the swap on its next notification.
10. Export settings, change a mode, import the file back, and confirm the mode is restored.

## Next iteration

The next useful step is to test this exact build on the S26 Ultra and inspect which notification fields Samsung exposes for contact avatars, action buttons and Now Bar rendering. Then the Samsung-specific layer can be tightened without changing the core listener architecture.

ALL-mode mirror tracking and the widget's live actions/`PendingIntent` are currently in-memory only and reset if the listener process is killed by the system; persisting whatever can safely be persisted (not the `PendingIntent`s themselves) would reduce how often that happens in practice.
