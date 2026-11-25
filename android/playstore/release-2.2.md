# Play Console: BetterGecko 2.2 (versionCode 4) — closed testing

Bundle: `android/app/build/outputs/bundle/release/app-release.aab` (build with `./gradlew bundleRelease`),
signed with the upload key in `~/Keys/bettergecko-upload.jks`.

## Release name

```
2.2 (4)
```

## Release notes (en-US, 500 max)

```
<en-US>
- AC hours now keep counting in the background, even when you don't open the app.
- Optional AC Hours Tracker: in Profile, connect a tracker server (URL and API key) for a complete monthly AC tally. Tap Save & Test to check the connection.
- Demo mode: explore the app with sample data, no GeyserGecko account needed.
</en-US>
```

Drop the demo-mode line if testers already received versionCode 3.

## Checks before rolling out

- Background refresh uses WorkManager, which merges `ACCESS_NETWORK_STATE`, `WAKE_LOCK` and
  `FOREGROUND_SERVICE` into the manifest (new since 2.1; none prompt the user). If Play Console
  asks for a foreground-service declaration, the app doesn't run expedited/foreground work.
- `SCHEDULE_EXACT_ALARM` (mode schedules) is unchanged since the first release; its existing
  Play declaration still applies.
- Data safety: the tracker API key is stored on the device and only sent to the tracker URL the user enters.
- versionCode 4 must be higher than anything already uploaded to any track.
