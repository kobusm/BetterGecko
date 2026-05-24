# BetterGecko

A native iOS companion app for the **GeyserGecko** solar geyser controller. BetterGecko provides real-time monitoring, detailed performance graphs, and device configuration — all from your iPhone or iPad.

---

## Features

### Summary
- Device list with live temperature and operating state (Solar Heating, Element Heating, Idle, Off)
- Pull-to-refresh and background auto-refresh
- Add multiple GeyserGecko devices by serial number (GSN)
- Per-device rename and remove
- Tap any device to open its detail view with energy stats

### Graphs
- Full performance history with selectable time ranges: **1H · 6H · 24H · 3D**
- **Temperature** chart with tap-to-inspect callout
- **PV Active** — binary chart showing when solar heating was active
- **AC Active** — binary chart showing when the element was active
- **PV Voltage** chart
- **MPPT Voltage** chart
- All graph timestamps shown in server time (SAST, UTC+2, 24-hour format)
- Export data as CSV (saved to Files)

### Settings
- Per-device temperature setpoints:
  - **AC Setpoint** — element heating target
  - **PV Setpoint** — solar heating target (must be ≥ AC setpoint)
- **Operating Mode** — Off / Solar Only / Solar + Element / Element Only
- Shows a warning when the account lacks control rights for the device

### Profile
- Displays the signed-in email address and account role
- Sign out

---

## Requirements

| Requirement | Version |
|-------------|---------|
| iOS | 17.0+ |
| Xcode | 16+ |
| Swift | 5.9+ |

Runs on iPhone, iPad, and Mac (via Mac Catalyst).

---

## Project Structure

```
BetterGecko/
├── API/
│   ├── APIClient.swift          # Authenticated HTTP actor (GET / POST, token refresh)
│   ├── AuthAPI.swift            # Login, logout, session restore, keychain
│   ├── DeviceAPI.swift          # Performance history, hasControl, setTemperature, setMode
│   └── KeychainHelper.swift     # Secure credential storage
├── Models/
│   ├── AppState.swift           # @Observable app-wide state (session, devices)
│   └── Models.swift             # GeckoDevice, HistoryPoint, GeckoState, TimeRange
└── Views/
    ├── MainTabView.swift         # Root TabView (Summary · Graphs · Settings · Profile)
    ├── DashboardView.swift       # Summary tab — device list
    ├── DeviceDetailView.swift    # Device detail — energy stats grid + time range picker
    ├── EnergyView.swift          # Graphs tab — all performance charts
    ├── DeviceSettingsView.swift  # Device configuration (temperature, mode)
    ├── SettingsView.swift        # Settings tab — navigates to DeviceSettingsView
    ├── ProfileView.swift         # Profile tab — account info + sign out
    ├── AddDeviceSheet.swift      # Add device by GSN
    ├── LoginView.swift           # Email + password login
    ├── SplashView.swift          # Launch screen / session restore
    └── InfoView.swift            # App info
```

---

## Building

1. Install [XcodeGen](https://github.com/yonaskolb/XcodeGen):
   ```bash
   brew install xcodegen
   ```

2. Clone the repository and generate the Xcode project:
   ```bash
   git clone https://github.com/kobusm/BetterGecko.git
   cd BetterGecko
   xcodegen generate
   ```

3. Open `BetterGecko.xcodeproj` in Xcode and run on a device or simulator.

> No third-party dependencies — the project uses only Apple frameworks (SwiftUI, Charts, Foundation).

---

## Architecture

| Layer | Technology |
|-------|-----------|
| UI | SwiftUI |
| State | `@Observable` (`AppState`) |
| Networking | `async/await`, `URLSession`, actor-isolated `APIClient` |
| Charts | Swift Charts |
| Persistence | `UserDefaults` (devices, settings), Keychain (credentials) |

### API

All requests target `https://geckows.co.za` and require:
- `Authorization: Bearer <token>` — obtained at login and refreshed automatically on 401
- Version headers: `X-App-Version`, `X-Android-App-Version`, `X-IOS-App-Version` set to `17`

Key endpoints used:

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/auth/signin` | Log in, returns session token |
| POST | `/app/deviceid` | Resolve device ID from GSN |
| GET | `/app/getPerformanceHistory` | Telemetry arrays (temperature, state, voltage, power) |
| GET | `/app/hasControl` | Check if account can change mode/settings |
| POST | `/app/setTemperature` | Set AC and PV temperature setpoints |
| POST | `/app/setMode` | Set operating mode |

### Settings persistence

The GeyserGecko API has no endpoint that returns current setpoints or operating mode. The app persists the last successfully saved values in `UserDefaults` (keyed by GSN) and loads them on next launch.

---

## License

MIT — see [LICENSE](LICENSE).
