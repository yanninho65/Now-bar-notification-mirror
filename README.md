# Now Bar Mirror

Android app that mirrors selected apps' notifications into persistent notification(s) intended to be eligible for Samsung One UI / Android Live Update surfaces.

## What it does

- Listens to notifications using `NotificationListenerService`.
- Ignores its own notifications, ongoing notifications, and group-summary bundles (e.g. WhatsApp's "X new messages").
- Only mirrors apps you've explicitly selected — nothing is mirrored by default.
- Per app, you choose one of two modes:
  - **Dernière notif (LATEST)** — all apps in this mode share a single mirror slot; whichever posts most recently occupies it, replacing whatever was shown before.
  - **Toutes (ALL)** — every distinct notification from this app gets its own persistent mirror, shown at the same time.

  Example: Signal and WhatsApp set to "Toutes", Le Monde and Mediapart set to "Dernière notif". Two WhatsApp messages + two Signal messages + one Mediapart notification all show at once (five mirrors). A Le Monde notification arriving next replaces the Mediapart one, since both share the LATEST slot.
- Copies title and text.
- Uses the notification's large icon/contact image when available, otherwise the source app icon.
- Copies up to three standard action buttons and reuses their original `PendingIntent`.
- If an original notification is removed, its mirror is removed.
- If a mirror is removed by the user (swipe, or "clear all"), the original notification is cancelled through the notification-listener API. Removing our own mirror to replace its content (LATEST-mode swap, ALL-mode update) does **not** trigger this — only a genuine user dismissal does.
- A "Service actif" switch lets you pause mirroring entirely without uninstalling or revoking notification access; existing delete-sync keeps working for mirrors already showing while paused.
- Settings (per-app modes + service on/off) can be exported to / imported from a JSON file via the system file picker.
- On Android 16+, requests a promoted ongoing notification so the system can consider it for Live Update surfaces.
- Includes the Samsung ongoing-activity application metadata used by current One UI implementations.

## Choosing which apps to mirror

Open **Now Bar Mirror** → **Applications à mirrorer**. Each installed app with a launcher icon is listed with a mode selector (Aucun / Dernière notif / Toutes). The same screen has the service on/off switch and the export/import buttons.

## Project layout

```
app/src/main/java/com/yann/nowbarmirror/
├── MainActivity.kt                    entry screen: permissions + link to app selection
├── MirrorNotificationListener.kt      the NotificationListenerService itself
└── settings/
    ├── MirrorMode.kt                  NONE / LATEST / ALL
    ├── AppMirrorPrefs.kt              per-package mode storage (SharedPreferences)
    ├── ServicePrefs.kt                service on/off flag
    ├── SettingsBackup.kt              JSON export/import of the above
    ├── AppSelectionActivity.kt        the settings screen
    └── AppSelectionAdapter.kt         RecyclerView adapter for the app list
```

## Important limitation

The Android notification listener API can observe and cancel notifications, and notification actions expose their `PendingIntent`s. Samsung ultimately controls whether and how an ongoing notification appears in the Now Bar. This project therefore deliberately uses the public Android notification APIs plus the Samsung ongoing-activity hint; it does not attempt to depend on undocumented Samsung framework internals.

Inline-reply `RemoteInput` actions are not yet reconstructed in the mirror. Standard action buttons are copied in V1.

Since the app targets API 30+, it declares a `<queries>` entry for the `MAIN`/`LAUNCHER` intent in the manifest — without it, Android's package-visibility restrictions would hide every user-installed app (WhatsApp, Signal, etc.) from the app-selection screen, leaving only system apps visible.

## Build

The APK is built via the GitHub Actions workflow in this repo rather than locally in Android Studio (which runs slowly on the current dev machine). To build locally instead:

1. Open this folder in Android Studio.
2. Let Android Studio install/sync the Android Gradle Plugin and dependencies if requested.
3. Use JDK 17.
4. Build `app` -> `assembleDebug`.
5. Install the resulting debug APK on the Galaxy.
6. Open **Now Bar Mirror**, enable **Notification access**, and pick which apps to mirror.
7. On Android 13+, allow the app's own notifications.
8. Send yourself a test notification from another app.

## Recommended first test

Use Samsung Messages, WhatsApp, Signal or another app that produces a normal alert notification with a title, text, image and action buttons.

1. Set it to "Toutes" (or "Dernière notif") in the app-selection screen.
2. Confirm the mirror appears.
3. Confirm the source notification remains visible.
4. Send a second, different notification and confirm the previous behavior (replace for LATEST, new mirror for ALL) matches expectations.
5. Removing the source removes the mirror.
6. Removing the mirror removes the source.
7. Tapping an action performs the source app's action.
8. Toggle "Service actif" off, confirm new notifications from selected apps stop mirroring, then toggle it back on.
9. Export settings, change a mode, import the file back, and confirm the mode is restored.

## Next iteration

The next useful step is to test this exact build on the S26 Ultra and inspect which notification fields Samsung exposes for contact avatars, action buttons and Now Bar rendering. Then the Samsung-specific layer can be tightened without changing the core listener architecture.

ALL-mode mirror tracking is currently in-memory only and resets if the listener process is killed by the system; persisting it (e.g. to SharedPreferences) would make it survive a restart.
