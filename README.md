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

Output: `artifacts/notion-tasks.apk` and its SHA-256 checksum. The script runs unit tests and Android lint, builds release, aligns it and signs it with a locally generated key. **Back up `~/.android/notion-todo-widget-signing/` securely.** Future installations over this app require the same signing key. Never commit that directory. Override its location with `NOTION_SIGNING_DIR` if needed.

For development:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# With an emulator or phone connected:
./gradlew :app:connectedDebugAndroidTest
adb install -r artifacts/notion-tasks.apk
```

Debug and release have different signatures. If switching between them, uninstall the old variant first (this clears its settings).

## Implementation and verification

Native Java Android activities and a resizable, scrolling `RemoteViews` widget; WorkManager handles refresh/completion outside the launcher, OkHttp calls Notion directly. The checkbox uses an activity PendingIntent that immediately queues completion and closes, avoiding background launch restrictions; adding/editing opens a compact task dialog.

Notion API is pinned to `2025-09-03` and uses [data source endpoints](https://developers.notion.com/guides/get-started/upgrade-guide-2025-09-03), [paginated queries](https://developers.notion.com/reference/query-a-data-source), and page property patches. Widget implementation follows [Android collection widget APIs](https://developer.android.com/develop/ui/views/appwidgets/collections).

Automated API tests use a mock HTTP server to verify pagination, headers, database/data-source resolution, property IDs, completion, creation, existing tags, error handling, and no automatic creation retries. Android instrumentation checks encrypted storage, cached data, setup/layout inflation, and widget registration. No real Notion task is created or changed by these tests. Live workspace verification requires configuring your connection on the device.
