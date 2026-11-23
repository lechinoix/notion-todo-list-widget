# Notion Tasks — Android home-screen widget

A personal, sideloadable Android app for a Notion task database. No Play Store, server, subscription, or Notion credentials in the APK.

- Scroll through unfinished tasks on your home screen.
- Tap a circle to mark a task done in Notion.
- Tap **+** to create a task, with optional existing tags.
- Tap a task title to add or remove existing tags.
- Tap **↻** to refresh, or the widget heading to open settings and the full list.

Android 8.0+ (API 26). The app supports a **Title**, **Status**, and **Multi-select** property, matching the TODO table in the supplied screenshot. Property names can be different: choose them during setup. All widget instances use the same table. The selected completed status is hidden; other statuses remain visible. This queries the source table, not the filters of an individual Notion board view.

## Install and connect

1. Transfer `artifacts/notion-tasks.apk` to your Android phone and open it. Allow installation from that file manager/browser when Android asks. This is a normal APK; no store account is needed.
2. Create an **internal connection** in [Notion's connection settings](https://www.notion.so/profile/integrations), in the workspace containing your TODO table. Enable **Read content**, **Insert content**, and **Update content**.
3. Open the **original TODO database** in Notion (not just the page containing its linked view). Use **••• → Connections** to add the connection. Copy that database's link.
4. Open **Notion Tasks**. Enter the connection secret and database link. If it contains multiple data sources, choose the task table. A direct data source ID also works.
5. Select your title, Status and Tag properties. Choose the initial status (for example **Not started**) and completed status (for example **Done**). Press **Save and load tasks**.
6. Tap **Add to home screen**, or long-press your home screen → **Widgets → Notion Tasks**. Resize as desired.

The secret stays encrypted using Android Keystore on your device. It is entered in the app, never in the repository or APK. App data is excluded from cloud backup and device transfer; reconnect after reinstalling or moving phones. Task titles/tags are cached in the app's private storage and visible in the home-screen widget.

## Updating without losing the token

Install the new APK over the existing app (or use `adb install -r artifacts/notion-tasks.apk`). Keep the same application ID and signing key. Android preserves the app's private preferences and Keystore entry during an in-place update, so the encrypted token and table settings remain available. Do **not** uninstall or clear app data first. If Android reports a signature mismatch, rebuild using the original signing key; uninstalling would erase the saved connection.

## Sync and errors

- Successful edits update the cache/widget immediately and refresh from Notion.
- Background refresh runs approximately every 30 minutes. Android battery-saving policies can delay it. Use **↻** for a manual refresh.
- Cached tasks remain visible without internet. This version does **not** queue offline edits; an unsuccessful completion remains in the list with an error. Tap its circle to retry after restoring connectivity.
- Creation requests are never automatically retried: if a response is lost, check the refreshed list before creating again to avoid duplicates.
- Tag edits send existing option IDs only. They preserve changes made elsewhere since the editor opened where possible. Notion has no atomic compare-and-swap for these updates; edits made during the final read/write window can still conflict.
- A renamed property keeps working through its ID. A renamed completed status is resolved on refresh. If a property/status is deleted, reconnect in Settings.
- A `404` usually means the original database was not shared with this connection. A `401` indicates a rejected secret; `403` indicates insufficient capabilities.

## Build a signed APK

Requires JDK 17, Android SDK platform 35, Build-Tools 35.0.1, and internet for Gradle dependencies. Set `ANDROID_HOME`, or put `sdk.dir=/path/to/Android/sdk` in your untracked `local.properties`.

```sh
./scripts/build-apk.sh
```

Output: `artifacts/notion-tasks.apk` and its SHA-256 checksum. The script runs unit tests and Android lint, builds release, aligns it and signs it with your existing local key. On a first-ever build only, pass `NOTION_ALLOW_NEW_SIGNING_KEY=1` to generate a key. **Back up `~/.android/notion-todo-widget-signing/` securely.** Future installations over this app require the same signing key. Never commit that directory. Override its location with `NOTION_SIGNING_DIR` if needed. The build refuses to generate a replacement key by default, preventing accidental signature changes during updates.

For development:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# With an emulator or phone connected:
./gradlew :app:connectedDebugAndroidTest
adb install -r artifacts/notion-tasks.apk
```

Debug and release have different signatures. If switching between them, uninstall the old variant first (this clears its settings).

## Implementation and verification

Native Kotlin Android activities and a resizable, scrolling `RemoteViews` widget; WorkManager handles refresh/completion outside the launcher, OkHttp calls Notion directly. Widget rows use a broadcast PendingIntent: checking a task queues completion without opening an activity, while tapping its title opens the tag editor in a separate task. Adding a task also opens a separate task, so saving or cancelling returns to the launcher even when the main app already has a screen open. The create checkmark sits to the right of the title input.

After Notion confirms completion, the widget requests a checked/struck-through row, a dimmed row 250 ms later, and removal after another 250 ms. This is best-effort, stepped feedback rather than a frame-by-frame animation: launchers may delay or combine RemoteViews updates. Smooth sliding of the remaining ListView rows is not controlled by this app. Failed writes retain the task and display a retry message.

Notion API is pinned to `2025-09-03` and uses [data source endpoints](https://developers.notion.com/guides/get-started/upgrade-guide-2025-09-03), [paginated queries](https://developers.notion.com/reference/query-a-data-source), and page property patches. Widget implementation follows [Android collection widget APIs](https://developer.android.com/develop/ui/views/appwidgets/collections).

Automated API tests use a mock HTTP server to verify pagination, headers, database/data-source resolution, property IDs, completion, creation, existing tags, error handling, and no automatic creation retries. Android instrumentation checks encrypted storage, cached data, setup/layout inflation, and widget registration. No real Notion task is created or changed by these tests. Live workspace verification requires configuring your connection on the device.

### Device checks for widget interaction changes

- Install the update over a release signed with the same key; confirm tasks load without entering the secret again.
- Leave Settings open, go home, then create a task from **+**. Confirm the checkmark is beside the input and successful save returns home. Repeat for title/tag editing and cancellation; ordinary in-app editing should return to the app.
- Complete a widget task with the app closed and with Settings in the background. Confirm no activity appears, checked/dimmed feedback precedes removal, and the remaining rows fill the gap. Check Android 8 and a recent Android version/launcher, including title-to-editor navigation.
- Disable connectivity and complete a task; confirm it stays visible with a retry message. Restore connectivity and retry, including rapid repeated taps and multiple widget instances.
