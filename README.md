# Now Bar Mirror

Prototype Android app that mirrors the most recently posted non-ongoing notification into a persistent notification intended to be eligible for Samsung One UI / Android Live Update surfaces.

## What it does

- Listens to notifications using `NotificationListenerService`.
- Ignores its own notification and ongoing notifications.
- Keeps one mirrored notification: the latest eligible notification wins.
- Copies title and text.
- Uses the notification's large icon/contact image when available, otherwise the source app icon.
- Copies up to three standard action buttons and reuses their original `PendingIntent`.
- If the original notification is removed, the mirror is removed.
- If the mirror is removed, the original notification is cancelled through the notification-listener API.
- On Android 16+, requests a promoted ongoing notification so the system can consider it for Live Update surfaces.
- Includes the Samsung ongoing-activity application metadata used by current One UI implementations.

## Important limitation

The Android notification listener API can observe and cancel notifications, and notification actions expose their `PendingIntent`s. Samsung ultimately controls whether and how an ongoing notification appears in the Now Bar. This project therefore deliberately uses the public Android notification APIs plus the Samsung ongoing-activity hint; it does not attempt to depend on undocumented Samsung framework internals.

Inline-reply `RemoteInput` actions are not yet reconstructed in the mirror. Standard action buttons are copied in V1.

## Build

1. Open this folder in Android Studio.
2. Let Android Studio install/sync the Android Gradle Plugin and dependencies if requested.
3. Use JDK 17.
4. Build `app` -> `assembleDebug`.
5. Install the resulting debug APK on the Galaxy.
6. Open **Now Bar Mirror** and enable **Notification access**.
7. On Android 13+, allow the app's own notifications.
8. Send yourself a test notification from another app.

## Recommended first test

Use Samsung Messages, WhatsApp or another app that produces a normal alert notification with a title, text, image and action buttons. Confirm:

1. The mirror appears.
2. The source notification remains visible.
3. Removing the source removes the mirror.
4. Removing the mirror removes the source.
5. Tapping an action performs the source app's action.

## Next iteration

The next useful step is to test this exact build on the S26 Ultra and inspect which notification fields Samsung exposes for contact avatars, action buttons and Now Bar rendering. Then the Samsung-specific layer can be tightened without changing the core listener architecture.
