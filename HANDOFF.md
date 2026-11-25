# BetterGecko — Handoff (2026-11-25)

## Where things stand

- `main` is clean and in sync with `origin/main` at `df5f871` (Bump version to 2.2).
- Current version: **2.2** on both platforms — iOS `MARKETING_VERSION 2.2` / build 32, Android `versionName 2.2` / `versionCode 4`.
- **Android:** 2.2 (versionCode 4) is live on both Play closed testing tracks ("Closed testing - Groups" and "Alpha") since 2026-11-17. The next Play upload needs versionCode 5. Notes used: `android/playstore/release-2.2.md`.
- **iOS:** 2.2 is **Ready for Distribution** (live on the App Store) with build **32** (checked 2026-11-25). The repo's `CURRENT_PROJECT_VERSION` is now 32 to match; the next upload needs 33 or higher.
- `scripts/asc.py` isn't usable on this Mac yet: `~/.appstoreconnect/config.env` (key ID, issuer ID, key path) and the venv with `jwt`/`requests` are missing.

## What's in the repo

| Path | What it is |
|---|---|
| `BetterGecko/`, `BetterGeckoTests/`, `BetterGecko.xcodeproj` | iOS app (SwiftUI; `project.yml` is stale, see Gotchas). Tests: `ACRefreshTests`, `DemoTests`. |
| `android/` | Android app (Kotlin/Compose). Unit tests in `app/src/test/.../data/` (`AcRefreshTest`, `DemoTest`, `LogicTest`). |
| `worker/` | Cloudflare Worker `bettergecko-ac-hours` — server-side AC on-hours tally in D1 (see `worker/README.md`). |
| `site/` | Support / privacy / testers pages on Cloudflare Pages project `bettergecko-app`. |
| `scripts/` | `asc.py` (App Store Connect API helper), icon and upload-key helpers. |
| `ANDROID_DEVELOPER_GUIDE.md` | Full spec of screens, API, models — the reference for platform parity. |

## Recent work (all on `main`, 2026-11-17)

1. **Demo mode** on both apps (lets reviewers/testers use the app without a GeyserGecko account).
2. **`bettergecko://` URL scheme** registered on iOS.
2. **AC-hours tracking**
   - Both apps keep a local `ACUsageLedger` (split on SAST midnight, gaps capped at 1 h, per-day max wins).
   - Background refresh: iOS `BGAppRefreshTask` (iOS decides when), Android WorkManager daily job.
   - iOS Keychain items moved to `AfterFirstUnlock` so background re-login works while locked (migrated on launch).
3. **Tracker worker** (`worker/`): cron every 6 h pulls the ~3-day history and folds it into D1. Returns exact seconds per day.
5. **Apps merge worker data** — Profile → *AC Hours Tracker* (URL + API key, Save & Test). Key stored in Keychain / SecureStore; cleared on sign-out; hidden in demo mode.

## Docs

- `docs/mppt-controller-circuit.md` + two SVGs (merged from `claude/mppt-controller-diagram-ydukmf`): a *representative* MPPT buck circuit inferred from the app's telemetry, not the real GeyserGecko hardware.

## Gotchas

- **`project.yml` is stale** (unchanged since the first commit): it still says version 1.0 / build 1 and has no test target. `BetterGecko.xcodeproj/project.pbxproj` is the real source of truth — running `xcodegen generate` would overwrite it and lose those settings.

- **Two Cloudflare projects with similar names:** `bettergecko-app` (Pages, this app's site) and `bettergecko-ac-hours` (Worker). The plain `bettergecko` project is the ESP32 controller dashboard — never deploy there.
- **Static API token** is duplicated in `worker/wrangler.toml`, `APIClient.swift` and `Api.kt`. If sign-in fails with "Invalid token", update all three.
- **Single-session risk:** the worker signs in with the same account; if the server allows one session per account it may kick the phone app out (apps re-login silently on 401).
- **Worker data gaps:** history only covers ~3 days, so >3 days without a successful cron run loses data. Check the `runs` table.
- The GeyserGecko API has no endpoint for current setpoints/mode — apps persist last-saved values per GSN locally.
- Secrets kept out of git: `*.p8` App Store Connect key (gitignored), `android/keystore.properties`, `android/local.properties`, worker secrets (`GECKO_EMAIL`, `GECKO_PASSWORD`, `API_KEY` via `wrangler secret`).

## Common commands

```bash
cd android && ./gradlew testDebugUnitTest                             # Android unit tests
cd worker && npm test && npm run deploy                               # worker tests / deploy
npx wrangler pages deploy site --project-name=bettergecko-app --branch=main   # site
```

## Suggested next steps

1. Decide when to promote Android 2.2 from closed testing (iOS 2.2 is already live).
2. Verify the worker cron is running cleanly (`runs` table / `/health`) and that iOS background refresh is actually firing on a real device.
3. README still describes the iOS app only and doesn't mention Android, demo mode, AC hours or the worker — worth a refresh.
