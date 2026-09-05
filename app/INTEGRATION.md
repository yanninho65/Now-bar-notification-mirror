# Where everything goes, and what changed

This zip mirrors your repo's folder structure from `app/` down — extract
it and merge/overwrite into your project root.

## Files

| File | Path | Status |
|---|---|---|
| `AndroidManifest.xml` | `app/src/main/AndroidManifest.xml` | replaces yours — adds `.settings.AppSelectionActivity` |
| `MainActivity.kt` | `app/src/main/java/com/yann/nowbarmirror/MainActivity.kt` | replaces yours — adds the "Applications à mirrorer" button |
| `MirrorNotificationListener.kt` | `app/src/main/java/com/yann/nowbarmirror/MirrorNotificationListener.kt` | replaces yours — see bug fix below |
| `MirrorMode.kt` | `app/src/main/java/com/yann/nowbarmirror/settings/MirrorMode.kt` | new |
| `AppMirrorPrefs.kt` | `app/src/main/java/com/yann/nowbarmirror/settings/AppMirrorPrefs.kt` | new |
| `ServicePrefs.kt` | `app/src/main/java/com/yann/nowbarmirror/settings/ServicePrefs.kt` | new |
| `SettingsBackup.kt` | `app/src/main/java/com/yann/nowbarmirror/settings/SettingsBackup.kt` | new |
| `AppSelectionAdapter.kt` | `app/src/main/java/com/yann/nowbarmirror/settings/AppSelectionAdapter.kt` | new |
| `AppSelectionActivity.kt` | `app/src/main/java/com/yann/nowbarmirror/settings/AppSelectionActivity.kt` | new |
| `activity_app_selection.xml` | `app/src/main/res/layout/activity_app_selection.xml` | new |
| `item_selectable_app.xml` | `app/src/main/res/layout/item_selectable_app.xml` | new |
| `mirror_mode_strings.xml` | `app/src/main/res/values/mirror_mode_strings.xml` | new |

Everything is now wired end to end: manifest registers the screen,
`MainActivity` has a button to open it, no manual step left.

## The bug: notifications disappearing on their own

Your original `mirror()` did `cancelMirror()` (cancel the shared
`MIRROR_ID` notification) immediately before re-posting it with new
content. Cancelling *our own* notification fires `onNotificationRemoved`
for our own package, same as a user swipe would — that's the event used to
say "the mirror was removed → cancel the original". That removal event
arrives asynchronously, and by the time it did, the tracked key had almost
always already moved on to the *new* original. So swapping the shared slot
to a new notification could end up silently cancelling that brand-new
original — the "notifications vanish on their own" symptom.

Fix, in `MirrorNotificationListener.kt`:
- Switched to the 3-argument `onNotificationRemoved(sbn, rankingMap, reason)`,
  which exposes *why* a notification was removed.
- For our own package, only `REASON_CANCEL` (user swipe) or
  `REASON_CANCEL_ALL` ("clear all") count as a real dismissal.
  `REASON_APP_CANCEL` (we cancelled it ourselves) is ignored.
- Dropped the explicit `cancelMirror()` before re-posting: `notify()` on an
  id that's already showing is an in-place update, so it no longer even
  generates the spurious self-removal — the reason filter above is a
  second, independent safety net on top of that.

Net effect: an original notification is now only ever cancelled when you
actually swipe its mirror away (or clear all), never as a side effect of a
new notification arriving.

## Per-app selection and modes

`AppMirrorPrefs` stores, per source package, `NONE` / `LATEST` / `ALL`
(default `NONE` — nothing is mirrored until you select it in the new
screen). `LATEST`-mode apps share one slot (`MIRROR_ID`) — whichever
posts last wins, exactly like Signal/WhatsApp on "Toutes" each keeping
their own mirror while Le Monde/Mediapart on "Dernière notif" share one.
`ALL`-mode apps each get a persistent mirror per distinct notification key,
tracked in an in-memory `allModeMirrors` map (resets if the listener
process is killed).

## Service on/off switch

`ServicePrefs.isEnabled()` gates the top of `onNotificationPosted` — when
off, no new mirror is created or updated, but removal handling (the
two-way delete sync) keeps running normally for whatever mirrors are
already showing. The switch lives at the top of the app-selection screen.

## Export / import

`SettingsBackup` reads/writes a small JSON document (per-app modes +
service on/off) using `org.json`, already built into Android — no new
dependency. The two buttons on the selection screen use the system file
picker (`ActivityResultContracts.CreateDocument` / `OpenDocument`), so no
storage permission is needed. This needs `androidx.activity` for
`registerForActivityResult` — almost certainly already pulled in
transitively by `androidx.appcompat`, but if Gradle complains, add:

```kotlin
implementation("androidx.activity:activity-ktx:1.9.0")
```
