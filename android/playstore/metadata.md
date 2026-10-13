# Google Play submission: BetterGecko 2.0.1 (Android, versionCode 1)

Paste-ready text for Play Console, adapted from the App Store listing (version 2.0.1).
The Android graphs offer 1H / 6H / 24H / 3D, and there is no Mac or iPad wording.

Package name: `com.bettergecko.app`. It is **permanent** once the first upload is accepted.

---

## App name (30 max)

```
BetterGecko
```

## Short description (80 max)

```
Monitor and control your GeyserGecko solar geyser: live temps, graphs, costs.
```
[77]

## Full description (4000 max)

```
BetterGecko is a companion app for GeyserGecko solar geyser controllers, built for a fast, clear experience on Android.

DASHBOARD
See all your GeyserGecko devices at a glance. The Summary tab shows each unit's current water temperature and live heating state: solar heating, AC heating, idle or off.

DEVICE DETAIL
Tap any device for a full breakdown:
• Current temperature and heating state
• PV (solar) voltage and MPPT voltage
• Time spent on solar, AC and idle
• Estimated electricity cost for this month and last month

ENERGY GRAPHS
See how your system performs over time with interactive charts:
• Water temperature, with tap-to-inspect
• PV solar voltage
• MPPT voltage
• Solar active periods
• AC element active periods
Choose 1H, 6H, 24H or 3D windows, and export any dataset as a CSV file.

DEVICE SETTINGS
• AC Setpoint: the temperature the element heats to
• PV Setpoint: the higher target the solar panels can push toward
• Operating Mode: Off, Solar Only, Solar + Element, or Element Only
• Mode schedules: switch operating mode automatically at set times
Settings load live from the device every time you open the screen.

ELECTRICITY COST
Enter your electricity rate (R/kWh) and element rating (kW) to see an estimate of what AC heating costs you.

MULTIPLE DEVICES
Manage more than one GeyserGecko unit from a single app.

PRIVATE
No analytics, no ads, no tracking. The app talks only to the GeyserGecko service.

REQUIREMENTS
Requires an existing GeyserGecko account and a registered GeyserGecko solar geyser controller. BetterGecko is a third-party companion app and is not affiliated with or endorsed by GeyserGecko.
```

## Release notes (500 max)

```
First Android release of BetterGecko.
```

---

## Graphics

| Asset | Requirement | File |
|---|---|---|
| App icon | 512 x 512 PNG, full-bleed square | `icon-512.png` (cropped from the iOS icon; Play applies the mask) |
| Feature graphic | 1024 x 500 | `feature-graphic.png` |
| Phone screenshots | 2 to 8, 9:16, 320 to 3840px a side | `screenshots/phone-*.png`: **still to capture** (needs a signed-in emulator) |

---

## Store settings

| Field | Value |
|---|---|
| App or game | App |
| Free or paid | Free (can't be changed to paid later) |
| Category | House & Home |
| Contact email | kobus@marneweck.com |
| Website | https://kobusm.github.io/bettergecko-support/ |
| Privacy policy | https://kobusm.github.io/bettergecko-support/privacy.html |

## App content answers

| Section | Answer |
|---|---|
| Contains ads | No |
| Target audience | 18+ (it controls household equipment) |
| News app | No |
| Government app | No |
| Financial features | None |
| Health | None |
| Content rating (IARC) | Category "Utility, Productivity, Communication, or Other". Answer No to everything: no user interaction, no sharing of location, no purchases, no unrestricted web access |
| App access | **All or some functionality is restricted.** Add instructions with a GeyserGecko test login (email + password) and the test serial `fce8c0de16e0` (same as the iOS review note). Enter the credentials yourself in Play Console; don't commit them here |

### Data safety

Verified against the source: the only network host is `geckows.co.za` (HTTPS; no `usesCleartextTraffic`, no network security config). No analytics, crash reporting, ads or Firebase.

- Does your app collect or share any of the required user data types? **Yes**
- Collected: **Personal info › Email address.** Purpose: Account management. Required (not optional). Not processed ephemerally (stored encrypted on device to stay signed in).
- Shared: **No.** Sending it to GeyserGecko to sign in is a user-initiated action the user expects, which Play exempts from "sharing".
- Encrypted in transit: **Yes**
- Way to request deletion: no data is held by the developer. The GeyserGecko account belongs to the GeyserGecko service. Signing out or uninstalling removes everything from the device.

Password: Play has no "password" data type. Credentials are covered by the email/account answer above.

### Permissions to expect questions about

- `SCHEDULE_EXACT_ALARM`: for mode schedules. This is the user-granted variant, so no Play declaration form is needed (that's only for `USE_EXACT_ALARM`).
- `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`: local schedule notifications and re-arming alarms after reboot. No declaration needed.

---

## Build and upload

1. Create the upload key and a signed bundle (once, in a terminal; it prompts for a password):
   `python3 scripts/make_upload_key.py`
   Output: `android/app/build/outputs/bundle/release/app-release.aab`
2. Play Console › Create app, fill in the sections above.
3. Testing › Internal testing (or Closed testing; new personal developer accounts need a 12-tester, 14-day closed test before Production) › Create release › upload the `.aab`, opt in to Play App Signing.
4. Bump `versionCode` in `android/app/build.gradle.kts` for every later upload.
