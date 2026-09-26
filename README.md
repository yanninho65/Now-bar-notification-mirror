# Now Bar Mirror

Android app for Yann's Samsung Galaxy phone + Galaxy Watch. Two-module Gradle project: phone app `app/`, Wear OS app `wear/` (same `applicationId` `com.yann.nowbarmirror`, same signing key — mandatory for the Data Layer).

Two independent jobs:

1. **Notification mirroring** — mirrors selected apps' notifications into persistent notifications eligible for the Samsung Now Bar, feeds three lock-screen widgets and the watch's **"Notification"** complication. The same listener also feeds the watch's **"Messages"** complication from the apps chosen in **Applications de messagerie (montre)** (independent of mirror modes).
2. **Live sport scores** — parses Sofascore's own notifications (the only source; no sport API any more) and drives the watch's **"Score en direct"** complication, whose tap opens the watch **Sport** screen (every active Sofascore match + the sport apps chosen on the phone).

## Phone UI

`MainActivity` = one card with 5 entries (no tabs) + a status line (`PermissionsActivity.missingCount`):
- **Applications à mirrorer** → `settings.AppSelectionActivity` — full-screen list (mode Aucun/Dernière/Toutes + "Titre ↔ texte" per app).
- **Sport** → `sport.SportActivity` — card button **Applications sport (montre)** (→ `MessageAppsActivity` with `EXTRA_KIND=sport`), then active Sofascore matches, "Dernière notification (auto)" first; tap = match followed by the watch (`SofascorePrefs`).
- **Applications de messagerie (montre)** → `settings.MessageAppsActivity` — code-built; selected apps first with ▲/▼ (order saved, sent to the watch), then the others alphabetically. Same screen for the sport apps (kind extra): backing prefs = `OrderedAppsPrefs` base (in MessageAppsPrefs.kt) → `MessageAppsPrefs` / `SportAppsPrefs` (default Sofascore only).
- **Paramètres** → `settings.SettingsActivity` — "Service actif", "Revenir à la précédente", "Actions dans le widget", export/import JSON (`SettingsBackup`, incl. ordered message apps `message_apps` and sport apps `sport_apps`).
- **Autorisations** → `settings.PermissionsActivity` — notification access (status of both listeners, one system screen), app notifications, **Associer la montre** (`WatchCompanionLink`), full-screen intent (Android 14+, optional when associated).
Shared styles `MainMenuButton` / `MainMenuDivider` / `MenuStatusText` (themes.xml). No viewBinding.

This README is a snapshot of current behavior for the next working session — not a changelog, no dates/quotes/test lists. Update it only when Yann asks.

## Session workflow (read first)

- Clone directly (public, no auth, branch `main`): `git clone https://github.com/yanninho65/Now-bar-notification-mirror.git`. Never push.
- Deliver changed files reproducing the repo tree — a zip if more than 2 files — never a patch. List explicitly any file Yann must **delete** by hand on GitHub (uploads can't delete).
- No Android SDK, no Google Maven in the sandbox. Real build = GitHub Actions (see [Build](#build)). **Before delivering, run the type-check**: `bash tools/compile-check/check.sh` from the repo root (downloads kotlinc 2.2.20 + android-36.jar on first run, then compiles `app/` minus UI files (activities + RecyclerView adapters) and `wear/` against `tools/compile-check/stubs/`). 0 errors = Kotlin-level OK; resources/manifest/real library signatures still only checked by CI. If code starts using a new androidx/Play Services API, add it to the stubs (and check the baseline compiles first to tell stub gaps from real errors).
- Yann works from his phone, so no logcat: some error paths show a `Toast` ("Widget: …") as a temporary diagnostic.
- Code comments are long and dated (history of each fix, Yann's quotes). Keep new comments shorter; don't rewrite old ones needlessly.

## Architecture at a glance

```
Phone                                                         Watch
─────                                                         ─────
MirrorNotificationListener ──┐                                PhoneDataListenerService
  (apps set LATEST/ALL)      ├─► WidgetAllNotificationsStore      ├─ /match        → MatchScoreStore       → ScoreComplicationService
SofascoreNotificationListener┘    (history, 6/kind)               ├─ /notification → NotificationInfoStore → NotificationComplicationService
MirrorNotificationListener ──► MessagesWatchSync (/messages) ───► ├─ /messages     → MessagesStore         → MessagesComplicationService
SofascoreNotificationListener ─► SportWatchSync (/sport) ───────► └─ /sport        → SportStore            → SportActivity (tap on Score en direct)
  (message apps, active notifs)                                                                              → MessagesActivity
  (com.sofascore.results)  ──► SofascoreWidgetStore (6 matches)                                              → NotificationDetailActivity
                                   │                                                                         PhoneRelay (action/dismiss/open)
            NowBarWidgetProvider.requestUpdate()  (coalesced, 300 ms)                                              │
                                   ▼                                                                               │
            refreshAllNow(): syncWatchToLatest() → WatchNotificationSync (/notification)                           │
                             4x1 + Compact 4x2 + Triple 4x2 widgets                                                │
            SofascoreNotificationListenerService.refresh() → WatchSync (/match)                                    │
            WearActionRelayService ◄── /notifdetail/{action,dismiss,open}, /msgdetail/{action,dismiss,open,openapp},
                                       /sportdetail/{follow,dismiss,open} ┘
```

Single source of truth: **"Dernière notif"** (widget LATEST view, the watch "Notification" complication, the detail screen) is always `WidgetAllNotificationsStore.get().first()` — never a separate store.

## Phone — notification mirroring (`MirrorNotificationListener`)

- Ignores its own notifications, ongoing, group summaries, media playback (`isMirrorableShape`). Mirrors only apps chosen in **Applications à mirorer**: **LATEST** (one shared mirror id `9001`, replaced by the most recent LATEST-mode post) or **ALL** (one mirror per original, ids from `9100`).
- Per-app "Titre ↔ texte" swap applies to the Now Bar mirror only — widgets/watch always show the original title/text.
- Copies title, text, image (`NotificationImageExtractor`: MessagingStyle photo → `EXTRA_PICTURE` → `getLargeIcon()` → `EXTRA_LARGE_ICON`), up to 3 actions (original `PendingIntent`s). Android 16+: promoted-ongoing request + Samsung ongoing-activity meta-data.
- Deletion sync both ways (user swipe of a mirror = `REASON_CANCEL`/`REASON_CANCEL_ALL` only). "Revenir à la précédente" (default on): dismissing the LATEST slot promotes the next still-active one.
- On (re)connect (`rebuildStateFromActiveNotifications`): rebuilds in-memory mirror bookkeeping from posted mirrors' extras (`mirror.original.key`), drops orphan mirrors, prunes the widget history against `getActiveNotifications()`, bootstraps mirrors for already-active notifications, then one batched `refillAllNotifsHistory`. `PackageUpdateReceiver` forces a rebind of both listeners after an app update.
- `isEligibleGeneric` = shape filters + not Sofascore + mode ≠ NONE: defines what counts in "Toutes notifs" and its "+X" badge (`WidgetAllNotificationsStore.saveActiveGenericCount`).
- Battery gating: `onNotificationPosted` reads the app mode first and returns for NONE before any `getActiveNotifications()`; `onNotificationRemoved` only prunes/refills/refreshes when the removed entry was tracked or is eligible; Sofascore removals are left to the Sofascore listener.
- "Service actif" switch pauses without revoking access (changing any switch or importing re-pushes `/messages`).

## Phone — watch "Messages" feed (`MessagesWatchSync`)

- Source = the notification center itself (`getActiveNotifications()` of the connected `MirrorNotificationListener`), never a separate store: non-ongoing, non-summary notifications of the apps in `MessageAppsPrefs` (ORDERED list, newline-joined string `packages_ordered`; legacy unordered set `packages` read as fallback; defaults until saved: WhatsApp(+Business), Google Messages, Samsung Messages, Signal, Telegram, Messenger), most recent first, max 10.
- Listener hooks: `onNotificationPosted`/`onNotificationRemoved` of a message app (before the mirror-mode gating) and `onListenerConnected` → `scheduleMessagesSync()` (coalesced 400 ms, main thread). "Service actif" off → empty list sent.
- `/messages` DataMap: `messages` (key, postTime, pkg, title, text, detailLines = MessagingStyle lines via shared `messageLines`, actionLabels), `apps` (every selected app launchable on the phone, in Yann's order: pkg, label, count), top-level assets `img_<i>` (contact photo, `NotificationImageExtractor`, cached per posting) and `icon_<pkg>`. Deduped by a text signature.
- `actionsFor`: the notification's actions minus those with a RemoteInput (inline reply not relayed), max 3; the watch's index refers to this filtered list, recomputed at fire time.
- `appEntries`/`appsSignature`/`putApps`/`putIcons` are public and shared with `SportWatchSync` (same "apps" format and counting rules for both watch screens).
- Unread count per app: distinct conversations (shortcutId → conversation title → title); mail apps (known packages or CATEGORY_EMAIL) count distinct subjects (InboxStyle lines, else EXTRA_TEXT). Summaries only used if the app posted nothing else.
- Watch → phone: `/msgdetail/{action,dismiss,open}` (JSON `key`) → `MirrorNotificationListener.fireMessageAction` / `dismissMessage` / `openMessageOnPhone` (static entry points on the connected instance, run on its main thread, against the live notification). `/msgdetail/openapp` (JSON `pkg`) → `WearActionRelayService.openAppOnPhone`. Both "open" paths reuse `NowBarWidgetProvider.postOpenOnPhone` (see "Aff. sur tél." in the Watch section).

## Phone — Sport (`sport.SofascoreNotificationListenerService`)

- Separate listener with its own system toggle ("Now Bar Mirror — Sport (Sofascore)"). One Sofascore notification = one match, updated in place (`InboxStyle`, `EXTRA_TEXT_LINES` most recent first, max 6). Never groups by `groupKey` (Sofascore puts all matches in one group).
- Teams from title split on `" - "` or `"@"` (American sports, order kept). Score/status parsed from text by `SofascoreNotificationParser` (football/handball incl. ET/penalties, rugby, basketball quarters, tennis sets, table tennis/volleyball); unknown wording → raw-line fallback. Wording-dependent.
- Watched match (`SofascorePrefs`, picked in `SportActivity` or with the pin of the watch Sport screen): LATEST (auto) or CHOSEN (falls back to auto when its notification disappears). In auto mode `pickLatestAvoidingDuplicate` skips the match already shown as "Dernière notif" when ≥ 2 matches are active. Order matters: the history push runs **before** `refresh()` in every caller.
- `MatchResult` (Models.kt) = homeTeam, awayTeam, homeScore, awayScore, lastScorer, status. `/match` always sends `apiSource="SPORTS_DB"` (the watch still reads the key); no currentSet/kickoff sent. Sport APIs (TheSportsDB / Live Tennis API, overrides, follow service, INTERNET/foreground permissions, coroutines/lifecycle deps) were removed 24/09/2026; `PackageUpdateReceiver` deletes their leftover prefs (`sofascore_api_overrides`, `tennis_api_key`).
- `refresh()` sends `/match` only when `key|postTime|MatchResult` changed (`lastWatchSignature`); `sendCleared` also deduped. Match fields written by `WatchSync.putMatch` (shared by `/match` and each `/sport` item).
- `MatchResult` also carries, for the watch Sport screen only: `periodLabel` (long French label set by the football parser: "Match commencé", "Mi-temps", "2ème mi-temps" (after a goal: current half from the minute, >45 → 2ème, >90 → Prolongation), "Min 23 · Carton rouge", a "Correction du score" line → the period in progress (`periodAt`: last marker/timed event before it, e.g. "Mi-temps", "2ème mi-temps"), "1ère/2ème prolongation", "Tirs au but", "Match terminé"…), `eventKind` (PERIOD/GOAL/OTHER, sent but currently unused on the watch) and `goals` (`goalsOf(lines, homeTeam, awayTeam)`: every timed goal + in-match missed penalty ("65' Penalty manqué : <Team> <Player>", side from the team-name prefix) + red card ("87' Carton rouge: <Player> (<Team>)", side from the team in parentheses, else prefix) still in the 6 lines, most recent first, each encoded `side\tminute\tname\tkind` (kind `goal`/`penmiss`/`red`), sent as DataMap key **`scorers`** (was `goals`; the watch still decodes legacy `goals`). Goals without minute ("But: [2] - 0 <Team>") are tracked for bookkeeping but NOT sent (the name is the team's; wait for the timed line). "Penalty accordé" ignored. A "Correction du score" line removes the cancelled goal(s) — side from the bracket, else deduced from the previous score).
- **`/sport`** (`SportWatchSync`, **non-urgent** except while the watch Sport screen is open: it sends `/sportdetail/watching` `{open}` on resume/pause → `setWatchScreenOpen` opens/closes an urgent window (max 10 min) and forces one re-put; per match also `canMute` = the notification has Sofascore's "Mettre l'événement en silencieux" action (`muteActionOf`: semantic MUTE or label silenc/mute/sourdine), coalesced 400 ms on the listener's main thread, deduped by signature, images cached per posting): `matches` (every active Sofascore notif, max 20: key, postTimeMillis, title + putMatch fields, asset `img_<i>`), `followedKey` (CHOSEN and still active), `apps` (SportAppsPrefs with counts, same format as `/messages`), `icon_<pkg>`. Triggered by every `refresh()`, by posts/removals of a sport app, and `requestSportSync()` (app selection change, import).
- `toMatchResult` is cached per posting (key@postTime, LRU 64) — the parser is time-independent.
- Watch → phone `/sportdetail/{follow,dismiss,mute,open,watching}` (JSON `key`; follow with blank key = back to auto) → `SofascoreNotificationListenerService.followFromWatch` / `dismissFromWatch` (same path as the widget dismiss) / `muteFromWatch` (fires the mute action, then `dismiss` like the delete button) / `openOnPhoneFromWatch` (`postOpenOnPhone`, contentIntent else Sofascore launch; doesn't dismiss).

## Phone — widgets (`widget/`)

Three background-less widgets (lock screen via LockStar, or home screen), all rendered by `NowBarWidgetProvider`'s internal functions:

- **4x1** `NowBarWidgetProvider` — one row, three views (`WidgetViewModePrefs`): **Dernière notif**, **Sport** (5 matches, live/recent first via `sortedForWidget`, finished > 5 min demoted), **Toutes notifs** (5 entries, generic + Sofascore tiles). Left toggle: LATEST ↔ last of Sport/Toutes; right toggle: Sport ↔ Toutes.
- **4x2** `NowBarWidgetProviderCompact` — row 1 = Sport/Toutes strip (shared toggle state, current "Dernière notif" filtered out), row 2 = Dernière notif/peek.
- **4x2 double row** `NowBarWidgetProviderTriple` — row 1 generic-only notifs, row 2 Sofascore matches, row 3 Dernière notif/peek; no toggle; "+X" overflow badges from the TRUE active counts (generic count excludes Sofascore; minus 5 shown, minus 1 if row 3 shows that kind). Declared 4x2 (`minHeight=140dp`).
- Picker previews are static XML mockups (`*_preview.xml`), never touched by code.
- PendingIntent request-code ranges must stay distinct: 4x1 peeks 4300/4400, Compact 4700/4800, Triple 4900/5000, auto-close peek 4200, action-fire 4500+, open-on-phone cancel 4600, toggles 0/1, close-peek 2.
- **Peek**: tapping a tile shows it full-format (`WidgetPeekPrefs`) via a plain broadcast (`ACTION_OPEN_PEEK`) — **never an Activity** (any Activity launched from a lock-screen widget interacts with the keyguard; several trampoline attempts broke). Closes when its notification disappears (`closePeekIfShowing`, both listeners) or after 15 s (non-wakeup alarm, same-entry check).
- "Actions dans le widget" (off by default): up to 3 text actions. "Silent" actions (semantic mark-read/delete/archive/mute) are routed through the listener (`ACTION_FIRE_WIDGET_ACTION`) which fires the PendingIntent then cancels the source notification; others fire directly.
- Refresh model: listeners write stores synchronously, then `requestUpdate()` (throttled, max one refresh per 300 ms, any thread). `refreshAllNow()` runs `syncWatchToLatest()` (deduped by entry identity) then rebuilds only the widgets actually placed. Direct taps (`onReceive`) call `refreshAllNow()` immediately. The Compact/Triple bottom row and refresh body are shared (`renderPeekOrLatestRow`, `refreshProvider`).

### Stores

- `WidgetAllNotificationsStore` — history, max 6 per kind (GENERIC / SOFASCORE_MATCH, capped independently, merged by recency). Identity: Sofascore match or conversation (`isConversation`: shortcutId / CATEGORY_MESSAGE / MessagingStyle) collapses by key; anything else is (key, postTime). Self-heals with `pruneAgainstActive` + batched refill (`pushAll`) — one store write per batch.
- `SofascoreWidgetStore` — the 6 matches shown by Sport views + true active count. `pushSofascoreMatches` skips save/refresh when the kept list's signature is unchanged.
- Both: JSON in SharedPreferences (parsed once per change, `ParsedCache`), images as **content-addressed PNG files** (`WidgetImageFiles`: one file per posting key+postTime, kept across saves, orphans deleted; legacy slot files migrated), downscaled to 256 px, images passed lazily (`imageLoader`) so unchanged postings are never re-extracted/re-encoded. `save` is `@Synchronized` (main thread + API-poll thread).
- Live `PendingIntent`s/actions/detail lines are in memory only (`liveAllNotifIntents`, `liveSofascoreIntents`…): lost on process restart → taps fall back to launching the source app (Sofascore for a match).
- App-row label/launchability cached 10 min (`MessagesWatchSync.appInfo`); app-icon Assets encoded once per package (`WatchSync.iconAsset`, used by `/messages`, `/sport`, `/notification`).
- `BitmapUtils` (top-level): single `drawableToBitmap`/`circular`/`downscale`, LRU caches for app icons (`AppIcons`) and decoded store images (`ImageFiles`, keyed by path + lastModified).

## Watch (`wear/`)

- `PhoneDataListenerService` receives `/match`, `/notification`, `/messages`, `/sport` (one service), applies them with `FreshStore.setLive`, and requests the matching complication refresh. `/sport` is ignored (not decoded) while `SportActivity` is closed (`SportStore.onChanged == null`); the activity re-reads the persisted item on resume.
- `PhoneDataLayer.decodeImageAsset` caches decoded bitmaps by `Asset.digest` (LRU 8 MB): identical icons/photos resent on every push are not re-read/decoded.
- `PhoneDataLayer`: shared path-specific re-read of the persisted DataItem (`readMatch`/`readNotification`), asset decoding, complication refresh request. `FreshStore` (base of `MatchScoreStore`/`NotificationInfoStore`) orders values by the phone's send `timestamp`: live pushes always apply, re-reads apply only if not older (covers "cleared" too).
- Both complications re-read the persisted item on **every** request (local, cheap: codecs reuse the in-memory value — no asset decode — when `syncTimestamp` matches), fall back to memory only if the read fails, and cache the composed bitmap (`ComposedImageCache`). `UPDATE_PERIOD_SECONDS=600` (safety net only; updates are push-driven).
- **"Score en direct"** (`ScoreComplicationService`, LONG_TEXT / SMALL_IMAGE) — tap opens `SportActivity` (watch). `MatchClock` formats status per sport; `ComplicationImageComposer.composeRoundImage` draws image/score/period (320 px).
- **"Notification"** (`NotificationComplicationService`, SMALL_IMAGE) — tap opens `NotificationDetailActivity`: image + app icon header, title/text or detail lines (conversation messages via MessagingStyle, max 10; Sofascore lines, max 6), action pills + "Aff. sur montre" (only if the source package is launchable on the watch, `DetailViews.watchLaunchIntent`, shared with `MessagesActivity`) + "Aff. sur tél." + delete, all with press feedback, `SwipeDismissFrameLayout` (`androidx.wear:wear`, not Compose).
- **"Messages"** (`MessagesComplicationService`, SMALL_IMAGE) — `ComplicationImageComposer.composeMessagesImage`: up to 4 contact circles (most recent top-left; 1–3 laid out centered), app-icon badge bottom-right, initial on a colored disc when no photo. If the "Notification" complication is placed (`NotificationComplicationService.Presence`: instance ids persisted from onComplicationActivated/Deactivated + every request) and its entry key equals a message's key, that message is left out (`visibleMessages`) — **except when it is the only message** (then shown in both). Tap opens `MessagesActivity`.
- `MessagesActivity` — round-screen insets as fractions of the display (`DetailViews.applyRoundInsets`: sides 3 % like Sport, top 13 %, bottom 25 %; app row +7 %). Each message = one pill like Sport (`item_message.xml`, `bg_notif_pill`): image top-left (36dp, else the app icon) with the app icon as a badge at its bottom-right (`bg_app_badge`), title bold 14sp on its right, text (max 6 lines) or MessagingStyle lines (max 4 each) below. Truncated text → blue "•••" pill under it (`makeExpandable`, `bg_more_chip`), tap (or tap on the text) = full text, remembered per message/line across re-renders. Actions = round icon buttons on ONE line (`DetailViews.roundIconButton`, 36dp, 32 for 4, 28 for 5), mapped by label (`iconFor`, accent-insensitive): "lu"/read → `ic_mark_read`, silence/mute/sourdine → `ic_mute`, suppr/delete/corbeille → `ic_delete`; "Répondre"/reply dropped; unmapped labels stay text chips above the row; then `ic_watch` ("Aff. sur montre", only if installed on the watch) and `ic_phone`. The "delete on phone" round button sits alone under the row. Action index sent = the phone's index. `DetailViews` = shared pills/lines/press feedback/circular crop/round buttons, also used by `NotificationDetailActivity`. Top: ONE horizontally scrollable app row (`DetailViews.appIconRow`, shared with SportActivity; no watch/phone split): tap opens the app on the watch if installed there, else on the phone (`/msgdetail/openapp`); red count badge. Then every message (unfiltered); delete hides it locally until the next push. Live re-render via `MessagesStore.onChanged`.
- `SportActivity` (watch) — reuses `activity_messages.xml`, sides 3 % (pills near full width like the watch's own notification cards; app row keeps +7 % margins). App row (SportAppsPrefs), then one pill per active Sofascore match (`item_sport_match.xml`, `bg_notif_pill`): small notif image + title, score 34sp bold (raw strings `homeScoreText`/`awayScoreText`, brackets on `lastScorer`), period right under it in white, not bold (`MatchClock.longLabel`: phone `periodLabel`, else Q<n>/S<n>/1H/2H/HT/FT spelled out), scorers (`ScorerLine`, MatchScore.kt): minute centered, home scorer on its left / away on its right (name max 2 lines, 14sp white), missed penalty = name + "Pénalty manqué" (grey 12sp; "Pénalty X" if the long label is ellipsized) under it, red card = name + "Carton rouge" (red 12sp) under it, unknown side = centered; then 32dp round buttons pin / bell-off (`sport_mute_button`, only if `canMute`) / phone / delete. Followed match first (blue outline `bg_notif_pill_followed`, blue pin `bg_follow_active`; tap again = auto), then most recent first. Taps optimistic until the next push. Nothing filtered against "Dernière notif". Compact so a 4-scorer match fits the screen.
- No back stack: the 3 watch screens are `noHistory` + `excludeFromRecents` and every complication tap uses `NEW_TASK|CLEAR_TASK` (a tap always opens just that screen). (Phone app deliberately keeps its normal back stack.)
- All SMALL_IMAGE bitmaps are drawn on a **pure black disc** (`drawBlackBackground`) — transparent let the watch face's default grey slot show through.
- **Empty states** (`drawEmptyState`, same layout for all three, sized on WhatsApp's own complication: glyph ~88 px centered 62 px above the middle, "0" below): Messages = WhatsApp-style bubble without handset (`drawChatBubble`), Score en direct = football pitch (`drawPitch`, copy of the phone widget's `ic_football_pitch`), Notification = bell (`drawBell`, copy of `ic_notification_bell`). "Score en direct" LONG_TEXT still says "Aucun match".
- **Complication picker icons** (manifest `android:icon`, shown in Galaxy Wearable): `ic_messages` = bubble, `ic_score_complication` = pitch, `ic_notification` = bell — same drawings as the empty states.
- `PhoneRelay` sends `/notifdetail/{action,dismiss,open}` messages (JSON kind/key/postTime/actionIndex). Phone side `WearActionRelayService` → `NowBarWidgetProvider.fireAction` / `dismissEntry` / `openEntry`. **"Aff. sur tél."** (`postOpenOnPhone`, shared by `openEntry` and the Messages paths): if the watch is associated (`WatchCompanionLink`, CompanionDeviceManager association made in Autorisations — survives app updates, unlike the full-screen-intent access which the installer can reset), the target `PendingIntent` is sent directly with background-start opt-in (`MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS` on 36+). Unlocked + opened directly → done. Otherwise (locked, or not associated) it also posts the high-importance relay notification (channel `open_on_phone_v2`) with content + full-screen intent, auto-cancelled 2.5 s later if locked. Association + direct launch not yet confirmed on device.

## Battery rules (audited 26/09/2026)

Keep these when adding features: every phone→watch put is deduped by a signature; only what a complication shows is `setUrgent()` (`/match`, `/notification`, `/messages`); screen-only data (`/sport`) is non-urgent unless its screen is open; images/icons/parses are cached per posting or per package; listener work is gated by app mode/app lists before any `getActiveNotifications()`; complication `UPDATE_PERIOD_SECONDS=600` (push-driven); no foreground service, no wakeup alarm, no polling.

## Deletion rules (audited 25/09/2026)

Our code cancels a source notification ONLY from: the widget ✕ (tile/peek dismiss → `ACTION_DISMISS_WIDGET` of either listener), the watch delete buttons (`/notifdetail/dismiss`, `/msgdetail/dismiss`, `/sportdetail/dismiss`), a user swipe/clear of a Now Bar mirror (`REASON_CANCEL`/`REASON_CANCEL_ALL` only; cancels the original key stamped in THAT mirror's extras `mirror.original.key`, not in-memory state), a replayed mark-read/delete/archive/mute action (`WidgetAction.dismissesOnFire`, semantic MARK_AS_READ/DELETE/ARCHIVE/MUTE — the system would auto-cancel on a real tap; Yann wants mute, incl. Sofascore's "Mettre l'événement en silencieux", to delete), and the watch Sport bell-off button (`/sportdetail/mute`). Taps that open (widget tile, "Aff. sur tél.", peek) never cancel; Sofascore itself may clear its notifications when opened — not ours. Keep it that way when adding buttons.

## Known issues / limitations

- If Samsung battery management freezes the phone listeners, nothing reaches the watch or widgets until the app is force-stopped. Check the app is "Unrestricted" and not in sleeping apps before debugging watch code.
- Inline-reply `RemoteInput` not reconstructed. PendingIntent-dependent features (tap-to-open, actions, ALL-mode tracking) degrade after a process restart until new events arrive.
- Watch `MatchClock`/`MatchScore`/`MatchDataCodec` still contain the old LIVE_TENNIS / currentSet / kickoff branches (never fed by the phone any more; harmless, not cleaned).
- Sport parsing depends on Sofascore's wording; "Match commencé" briefly shows football-style 0-0 on every sport.
- `<queries>` for LAUNCHER apps and `com.sofascore.results` required (API 30+) — on the watch too (LAUNCHER, for "Aff. sur montre" in both watch screens / the watch row).
- "Messages" / "Notification" detail: no inline reply from the watch; "Aff. sur montre"/watch row open the app, not the conversation, and only when the watch app has the phone's package name (Samsung Messages may differ — unverified). Mail counts need the mail app ticked in the message-app list.
- The `settings` package's folder should be lowercase `settings/`; if an uppercase `Settings/` folder is still present in the repo, the move hasn't been finished.

## Project layout

```
app/src/main/java/com/yann/nowbarmirror/
├── MainActivity.kt                 5-entry menu + permissions status line
├── WatchCompanionLink.kt           watch association (CDM) + direct "Aff. sur tél." launch
├── MirrorNotificationListener.kt   mirroring listener (mirrors + generic history feed + /messages)
├── NotificationImageExtractor.kt   image extraction, shared by both listeners
├── BitmapUtils.kt                  shared bitmap helpers + icon/image caches
├── PackageUpdateReceiver.kt        rebinds both listeners after an app update
├── WatchNotificationSync.kt        "/notification" sender
├── MessagesWatchSync.kt            "/messages" sender (message apps, counts; app-row helpers shared with /sport)
├── WearActionRelayService.kt       receives /notifdetail/* from the watch
├── settings/                       MirrorMode, AppMirrorPrefs, ServicePrefs, LatestModePrefs,
│                                   WidgetActionsPrefs, SettingsBackup, AppSelectionActivity/Adapter,
│                                   MessageAppsPrefs (+ OrderedAppsPrefs base), SportAppsPrefs,
│                                   MessageAppsActivity, SettingsActivity,
│                                   PermissionsActivity
├── sport/                          SofascoreNotificationListenerService, SofascoreNotificationParser,
│                                   SofascorePrefs, SportActivity + SofascoreHomeAdapter,
│                                   WatchSync ("/match" sender + putMatch + bitmapToAsset),
│                                   SportWatchSync ("/sport" sender), Models (MatchResult)
└── widget/                         NowBarWidgetProvider (4x1 + all shared rendering/refresh),
                                    NowBarWidgetProviderCompact, NowBarWidgetProviderTriple,
                                    WidgetAllNotificationsStore, SofascoreWidgetStore, WidgetImageFiles,
                                    SofascoreMatchPresentation (phone copy of wear score/period formatting),
                                    WidgetPeekPrefs, WidgetViewModePrefs

wear/src/main/kotlin/com/yann/nowbarmirror/wear/
├── PhoneDataListenerService.kt     /match + /notification + /messages + /sport receiver
├── PhoneDataLayer.kt               shared re-read/decode/refresh + FreshStore + ComposedImageCache
├── ScoreComplicationService.kt     "Score en direct"
├── NotificationComplicationService.kt  "Notification"
├── NotificationDetailActivity.kt   detail screen
├── MessagesComplicationService.kt  "Messages"
├── MessagesActivity.kt             messages list screen (app rows + messages)
├── MessageInfo.kt                  MessageInfo/MessageApp/MessageList + MessagesStore + MessagesDataCodec (decodeApps shared)
├── SportActivity.kt                Sport screen (app row + match pills)
├── SportInfo.kt                    SportMatch/SportList + SportStore + SportDataCodec
├── DetailViews.kt                  shared Galaxy Watch-style views of both screens
├── PhoneRelay.kt                   watch → phone messages
├── MatchDataCodec.kt / NotificationDataCodec.kt
├── MatchScore.kt (+ MatchScoreStore) / NotificationInfo.kt (+ NotificationInfoStore)
├── MatchClock.kt                   per-sport status labels
└── ComplicationImageComposer.kt    SMALL_IMAGE bitmaps

tools/compile-check/                sandbox type-check (check.sh, genR.py, stubs/) — not part of any build;
                                    UI activities/adapters excluded (appcompat/material/recyclerview not stubbed)
```

## Build

GitHub Actions `.github/workflows/build.yml` ("Build debug APK", on push to `main` or manual): JDK 17, Gradle 8.13, AGP 8.13, Kotlin 2.2.20, compileSdk/targetSdk 36 (min 26 phone, 30 watch). Signs both APKs with the `DEBUG_KEYSTORE_B64` secret (generated once by the "Generate debug keystore" workflow).

1. Download artifacts `NowBarMirror-debug-apk` (phone) and, if the watch changed, `NowBarMirror-wear-debug-apk`.
2. Phone: install; Autorisations → notification access (both toggles), app notifications, associate the watch; then pick apps.
3. Place widgets (picker or LockStar). Watch: install via GeminiMan WearOS Manager, assign "Score en direct" (LONG_TEXT / round SMALL_IMAGE), "Notification" and/or "Messages" (round SMALL_IMAGE).
