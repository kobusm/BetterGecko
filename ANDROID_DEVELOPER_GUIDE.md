# BetterGecko — Android Developer Guide

A complete specification for porting the BetterGecko iOS app to Android. This document covers every screen, every API call, all data models, local persistence, and chart requirements.

---

## Table of Contents

1. [Recommended Tech Stack](#1-recommended-tech-stack)
2. [Authentication & API Overview](#2-authentication--api-overview)
3. [All API Endpoints](#3-all-api-endpoints)
4. [Data Models](#4-data-models)
5. [Local Persistence](#5-local-persistence)
6. [Navigation Structure](#6-navigation-structure)
7. [Screen Specifications](#7-screen-specifications)
8. [Chart Specifications](#8-chart-specifications)
9. [Business Logic](#9-business-logic)
10. [Error Handling](#10-error-handling)
11. [Timezone Handling](#11-timezone-handling)
12. [App-Wide State](#12-app-wide-state)

---

## 1. Recommended Tech Stack

| Concern | Recommendation |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose |
| Navigation | Navigation Compose |
| Networking | Retrofit 2 + OkHttp 4 + Gson |
| Async | Kotlin Coroutines + Flow |
| State management | ViewModel + StateFlow / UiState sealed classes |
| Charts | [Vico](https://github.com/patrykandpatrick/vico) (Compose-native) or MPAndroidChart |
| Credential storage | Android Keystore + EncryptedSharedPreferences |
| General persistence | SharedPreferences (JSON strings via Gson) |
| Minimum SDK | API 28 (Android 9) |
| Target SDK | Latest stable |

---

## 2. Authentication & API Overview

### Base URL

```
https://geckows.co.za
```

### Static App Token

Every request (including the login request) **must** include a static Bearer token in the `Authorization` header. This token never changes and is not user-specific:

```
a6d2818e0657b5efb2e3ef9fecfe4731
```

### Required Headers on Every Request

```
Authorization: Bearer <token — see token model below>
X-IOS-App-Version: 17          # iOS clients
X-Android-App-Version: 17      # Android clients
Content-Type: application/json
```

> The version header must be a **plain integer** (e.g. `17`). A missing or non-integer value (like a dotted `2.0.10`) is rejected **before** the password check with **HTTP 418** and body `{"customMsg":"Your app version is too old. Please update to continue using the system."}`. The official app currently sends `17`.

> **⚠ User-Agent block.** The server's nginx gateway returns a bare HTML **403 Forbidden** to any request whose `User-Agent` contains the string `bettergecko` (case-insensitive), before the request reaches the application. Set an explicit `User-Agent` that does not contain that string — the iOS client uses the official app's iPhone Safari UA: `Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148`. OkHttp's default (`okhttp/x.y`) is unaffected, but do not set a UA derived from the app name.

#### Two-token model (important)

There are **two** bearer tokens:

1. **Static app token** — a fixed, app-wide token (not user-specific). Current value, captured from the official app's live traffic:
   ```
   b0f6fab1e44009644b6a7d7858741316
   ```
   > ⚠ This token has been **rotated** by the server before. An old value (`a6d2818e0657b5efb2e3ef9fecfe4731`) still passes the version gate but is rejected **after a valid login with HTTP 403**. If signin starts returning 403, the static token has rotated again — re-capture it from the official app with a proxy (Proxyman/Charles).

2. **Session token** — returned in the `token` field of the `/auth/signin` response; represents the logged-in user.

**Which token each endpoint uses:**

| Endpoint | Bearer token |
|---|---|
| `POST /auth/checkuser` | static app token |
| `POST /auth/signin` | static app token |
| `POST /auth/logout` | static app token |
| `POST /app/deviceid` | static app token |
| `GET /auth/getUserInfo` | static app token |
| `GET /app/getPerformanceHistory` | **session** token |
| `GET /app/hasControl` | **session** token |
| `GET /uv/uvindex` | **session** token |
| `POST /app/setMode` | **session** token |
| `POST /app/setTemperature` | **session** token |

Rule of thumb: **`/auth/*` and `/app/deviceid` use the static app token; per-device data calls use the session token.**

> Signin also sets an `xsrf-token` cookie (`Set-Cookie: xsrf-token=…`). A standard cookie-jar (OkHttp `CookieJar`, or `URLSession`'s automatic cookie storage) handles it — it is **not** required as a request header on the calls observed.

### Session Token (User-Specific)

After a successful login, the server returns a **session token**. This session token is **not** placed in the `Authorization` header (which always uses the static app token above). Instead it is passed as a field in subsequent request bodies — see individual endpoint specs below.

### Auth Flow

```
1. App starts
2. Restore session:
   a. Load session token from SharedPreferences ("gg_token")
   b. If present, try any API call (e.g. getPerformanceHistory)
   c. If 401 → attempt re-login with stored credentials (EncryptedSharedPreferences)
   d. If re-login succeeds → retry original request
   e. If no stored session token → show LoginScreen

3. Login:
   POST /auth/signin  →  returns { token, email, role, ... }
   POST /app/deviceid  →  returns { deviceID, role }
   Save token + deviceID to SharedPreferences
   Save email + password to EncryptedSharedPreferences
   Navigate to main app

4. Logout:
   POST /auth/logout
   Clear all SharedPreferences keys
   Clear EncryptedSharedPreferences
   Navigate to LoginScreen
```

### Token Refresh (401 Handling)

Implement as an OkHttp `Authenticator`:

```kotlin
class TokenAuthenticator(
    private val authRepo: AuthRepository
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null  // give up after 2 tries
        val newToken = runBlocking { authRepo.reLogin() } ?: return null
        return response.request.newBuilder()
            // Note: Authorization header stays as static app token
            // Re-login updates the session token stored in prefs
            .build()
    }
}
```

In practice: when any API call returns 401, call `/auth/signin` again with stored credentials, save the new session token, then replay the original request.

---

## 3. All API Endpoints

All request bodies are JSON. **Authentication is via the `Authorization` header, not the body** — no endpoint takes a `token` field in its body. Which token goes in the header (static vs session) is per the table in Section 2. Device identifiers in bodies and query params use the key **`geckoSerialNumber`**.

> ⚠ The per-endpoint request/response JSON shapes in the sub-sections below were drafted from the iOS source and may show a `token` field in the body or a `gsn` param — disregard those; the authoritative shapes are in the quick-reference (Appendix B), captured from the official app's live traffic.

---

### POST `/auth/signin`

Log in with email and password.

**Request body:**
```json
{
  "email": "user@example.com",
  "pw": "userpassword"
}
```

**Response (200 OK):**
```json
{
  "token": "SESSION_TOKEN_STRING",
  "email": "user@example.com",
  "role": "OWNER"
}
```

> `role` may also be `"USER"` or `"VIEWER"`. Owners/Users can change settings; Viewers cannot.

After sign-in, immediately call `/app/deviceid` with the returned token.

---

### POST `/app/deviceid`

Resolve a device ID from the session token. Called once after login.

**Request body:**
```json
{
  "token": "SESSION_TOKEN_STRING"
}
```

**Response (200 OK):**
```json
{
  "deviceID": "DEVICE_ID_STRING",
  "role": "OWNER"
}
```

Save `deviceID` and `role` to SharedPreferences.

---

### POST `/auth/logout`

**Request body:**
```json
{
  "token": "SESSION_TOKEN_STRING"
}
```

**Response:** 200 OK (body ignored). Always clear local storage regardless of response.

---

### GET `/app/getPerformanceHistory`

Fetch telemetry history for a device. This is the primary data endpoint — used for graphs, stats, and even verifying that a GSN is valid when adding a new device.

**Query parameters:**
```
gsn=GECKO_SERIAL_NUMBER
token=SESSION_TOKEN_STRING
```

**Response (200 OK):**
```json
{
  "pvUsageWeek": 420,
  "acUsageWeek": 180,
  "savingsWeek": 23.50,
  "latestTemperature": 58.3,
  "latestState": "PV_HEATING",
  "latestPVVoltage": 342,
  "data": [
    {
      "timestamp": "2026-05-24T14:30:00.000+02:00",
      "temperature": 58.3,
      "pvVoltage": 342,
      "mpptVoltage": 380,
      "state": "PV_HEATING"
    },
    ...
  ]
}
```

> **Important:** The `data` array is returned **newest-first**. Reverse it before storing/displaying so that charts render left-to-right chronologically.

**Field notes:**
- `pvUsageWeek` — minutes of solar heating in the past week (multiply by 60 for seconds)
- `acUsageWeek` — minutes of element heating in the past week
- `savingsWeek` — estimated Rand savings from solar this week
- `latestTemperature`, `latestState`, `latestPVVoltage` — convenience fields for the current reading
- `state` string values: `"PV_HEATING"`, `"AC_HEATING"`, `"IDLE"`, `"OFF"` (see GeckoState below)
- `pvOn` — derive from state: `state == "PV_HEATING" ? 1 : 0`
- `acOn` — derive from state: `state == "AC_HEATING" ? 1 : 0`
- Timestamps include timezone offset (`+02:00`); parse with ISO 8601

---

### GET `/app/hasControl`

Check whether the current session account has permission to change device settings (temperature setpoints and operating mode).

**Query parameters:**
```
gsn=GECKO_SERIAL_NUMBER
token=SESSION_TOKEN_STRING
```

**Response (200 OK):**
```json
{
  "controlled": true
}
```

If `controlled` is `false`, disable all controls in DeviceSettingsScreen and show a warning: *"Your account does not have control rights for this device."*

---

### POST `/app/setTemperature`

Set AC and PV temperature setpoints.

**Request body:**
```json
{
  "token": "SESSION_TOKEN_STRING",
  "gsn": "GECKO_SERIAL_NUMBER",
  "acMax": 60,
  "pvMax": 70
}
```

- `acMax` — AC (element) heating target temperature in °C (integer)
- `pvMax` — PV (solar) heating target temperature in °C (integer); **must be ≥ acMax**

**Response:** 200 OK on success. Save values to SharedPreferences on success.

---

### POST `/app/setMode`

Set the operating mode.

**Request body:**
```json
{
  "token": "SESSION_TOKEN_STRING",
  "gsn": "GECKO_SERIAL_NUMBER",
  "geckoMode": "AC_AND_PV"
}
```

**`geckoMode` values:**

| String | Display Label |
|---|---|
| `"ALL_OFF"` | Off |
| `"PV_ONLY"` | Solar Only |
| `"AC_AND_PV"` | Solar + Element |
| `"AC_ONLY"` | Element Only |

**Response:** 200 OK on success. Save mode string to SharedPreferences on success.

---

## 4. Data Models

### GeckoDevice

Stored locally (SharedPreferences). Not returned directly by any API — constructed from user input + `/app/deviceid` response.

```kotlin
data class GeckoDevice(
    val geckoSerialNumber: String,   // GSN, e.g. "GG-1234-AB"
    val friendlyName: String?        // User-assigned nickname, nullable
) {
    val displayName: String
        get() = friendlyName?.takeIf { it.isNotBlank() } ?: geckoSerialNumber
}
```

### HistoryPoint

Derived by parsing the `data` array from `/app/getPerformanceHistory`, then reversing so index 0 is oldest.

```kotlin
data class HistoryPoint(
    val timestamp: Instant,          // parsed from ISO 8601 string
    val temperature: Double?,        // °C, may be null
    val pvVoltage: Int?,             // volts, may be null/0
    val mpptVoltage: Int?,           // volts, may be null/0
    val state: String?               // raw state string from server
) {
    val pvOn: Int get() = if (state == "PV_HEATING") 1 else 0
    val acOn: Int get() = if (state == "AC_HEATING") 1 else 0
}
```

### GeckoState

```kotlin
enum class GeckoState(val rawValue: String) {
    SOLAR_HEATING("PV_HEATING"),
    ELEMENT_HEATING("AC_HEATING"),
    IDLE("IDLE"),
    OFF("OFF"),
    UNKNOWN("");

    val label: String get() = when (this) {
        SOLAR_HEATING    -> "Solar Heating"
        ELEMENT_HEATING  -> "Element Heating"
        IDLE             -> "Idle"
        OFF              -> "Off"
        UNKNOWN          -> "Unknown"
    }

    val iconName: String get() = when (this) {  // use Material Icons equivalents
        SOLAR_HEATING    -> "wb_sunny"           // or Icons.Default.WbSunny
        ELEMENT_HEATING  -> "bolt"
        IDLE             -> "nightlight"
        OFF              -> "power_settings_new"
        UNKNOWN          -> "device_thermostat"
    }

    val color: Color get() = when (this) {
        SOLAR_HEATING    -> Orange
        ELEMENT_HEATING  -> Red
        IDLE             -> Blue
        OFF              -> Gray
        UNKNOWN          -> Gray
    }

    companion object {
        fun fromRawString(raw: String?): GeckoState =
            values().firstOrNull { it.rawValue == raw } ?: UNKNOWN
    }
}
```

### PersistedSettings

Stored per-device in SharedPreferences (see Local Persistence).

```kotlin
data class PersistedSettings(
    val acTargetTemp: Int = 60,      // °C, default 60
    val pvTargetTemp: Int = 70,      // °C, default 70
    val mode: String = "AC_AND_PV"  // default Solar + Element
)
```

### TimeRange

```kotlin
enum class TimeRange(val label: String, val durationSeconds: Long) {
    ONE_HOUR("1H",    3_600),
    SIX_HOURS("6H",   21_600),
    ONE_DAY("24H",    86_400),
    THREE_DAYS("3D",  259_200);

    fun cutoffInstant(): Instant = Instant.now().minusSeconds(durationSeconds)
}
```

---

## 5. Local Persistence

### SharedPreferences Keys

| Key | Type | Content |
|---|---|---|
| `gg_token` | String | Session token from `/auth/signin` |
| `gg_deviceid` | String | Device ID from `/app/deviceid` |
| `gg_manual_devices` | String (JSON) | JSON array of `GeckoDevice` objects |
| `device_settings_<gsn>` | String (JSON) | `PersistedSettings` JSON for each device |

**`gg_manual_devices` JSON shape:**
```json
[
  { "geckoSerialNumber": "GG-1234-AB", "friendlyName": "Home Geyser" },
  { "geckoSerialNumber": "GG-5678-CD", "friendlyName": null }
]
```

**`device_settings_<gsn>` JSON shape** (e.g. key `device_settings_GG-1234-AB`):
```json
{
  "acTargetTemp": 60,
  "pvTargetTemp": 70,
  "mode": "AC_AND_PV"
}
```

### EncryptedSharedPreferences Keys

Use [Jetpack Security](https://developer.android.com/topic/security/data) for credentials:

| Key | Type | Content |
|---|---|---|
| `gg_email` | String | User email |
| `gg_password` | String | User password (needed for token re-login on 401) |

```kotlin
val encryptedPrefs = EncryptedSharedPreferences.create(
    context,
    "bettergecko_secure_prefs",
    MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)
```

### Important: No Server-Side Settings Storage

> **The GeyserGecko API has no endpoint that returns current temperature setpoints or operating mode.** There is no "get settings" call. The app persists the **last successfully saved** values in SharedPreferences and loads them on next launch. If the user has never saved settings from this device, show defaults (AC: 60°C, PV: 70°C, Mode: Solar + Element).

---

## 6. Navigation Structure

```
App Entry
│
├── SplashScreen (session restore)
│       │
│       ├── [session valid] ──────────────────► MainScreen (BottomNavigation)
│       │
│       └── [no session] ─────────────────────► LoginScreen
│
└── MainScreen
        │
        ├── Tab 0: SummaryTab
        │       │
        │       ├── [0 devices] → EmptyState (Add Device button)
        │       ├── [1 device]  → DeviceDetailScreen (shown directly, no list)
        │       └── [2+ devices] → DeviceListScreen
        │                               └── (tap device) → DeviceDetailScreen
        │
        ├── Tab 1: GraphsTab
        │       └── EnergyScreen (charts for selected device)
        │
        ├── Tab 2: SettingsTab
        │       └── DeviceSettingsScreen (embedded, for selected device)
        │
        └── Tab 3: ProfileTab
                └── ProfileScreen (email, role, sign out)
```

**Modal flows (launched from any tab):**
- AddDeviceSheet — bottom sheet, triggered from + button on SummaryTab
- DeviceRename — alert dialog, triggered from overflow menu in DeviceDetailScreen

---

## 7. Screen Specifications

### 7.1 SplashScreen

**Purpose:** Restore session on app launch; route to login or main.

**Behavior:**
1. Show app logo (geyser/solar icon) centred on screen.
2. Load `gg_token` from SharedPreferences.
3. If token exists: call any authenticated endpoint (e.g. `getPerformanceHistory` for first saved device).
   - Success → navigate to MainScreen.
   - 401 → attempt re-login with EncryptedSharedPreferences credentials.
     - Re-login success → navigate to MainScreen.
     - Re-login failure → navigate to LoginScreen.
4. If no token → navigate to LoginScreen immediately.
5. Timeout after ~10 s → navigate to LoginScreen with error toast.

---

### 7.2 LoginScreen

**Purpose:** Email + password login.

**UI elements:**
- Large app icon (geyser/lizard motif, green tint) — centred, ~120 dp
- `"BetterGecko"` title below icon
- Email `TextField` (keyboardType = email)
- Password `TextField` (visualTransformation = PasswordVisualTransformation)
- `"Sign In"` button (green, full-width, rounded) — disabled while loading
- `CircularProgressIndicator` while signing in
- Error text below button on failure

**On tap "Sign In":**
1. Validate fields not empty.
2. POST `/auth/signin` → on success, save token + email + password.
3. POST `/app/deviceid` → save deviceID.
4. Navigate to MainScreen (clear back stack).
5. On error: show inline error message (e.g. "Invalid email or password").

---

### 7.3 SummaryTab (DashboardView equivalent)

**Top-level tab.** Behaviour depends on number of saved devices.

#### 7.3a Empty State (0 devices)
- `ContentUnavailableView` equivalent: icon (lizard), title "No Devices", description "Add your GeyserGecko device using the GSN printed on the unit's screen."
- Prominent "Add Device" button (green, `.borderedProminent` style)
- `+` FAB or toolbar button always visible

#### 7.3b Single Device (shows DeviceDetailScreen directly)
- No list — render DeviceDetailScreen inline as the Summary tab content.
- Navigation title: device's `displayName`

#### 7.3c Multi-Device List
- `LazyColumn` of `DeviceRowItem` cards
- Pull-to-refresh
- Swipe-to-delete on rows
- `+` button in top-right toolbar to open AddDeviceSheet
- Refresh icon in top-left toolbar; animates (360° rotation) while refreshing
- On tap row → navigate to DeviceDetailScreen

**DeviceRowItem layout (horizontal):**
```
[Circle icon 48dp]  [Device name (bold)]    [Temperature (title2.bold)]
                    [State label (caption)]  [PV voltage (caption2)]
```

- Circle background colour = state colour at 15% opacity
- Icon inside circle = state icon (sun/bolt/moon/power)
- Temperature colour: ≥65°C → Red, ≤30°C → Blue, else Orange
- If temperature not yet loaded → show small ProgressIndicator in place of temperature
- PV voltage row only shown if `pvVoltage > 0`

**Refresh behaviour (multi-device):**
- `withTaskGroup` parallel: for each device, call `getPerformanceHistory`, read `latestTemperature`, `latestState`, `latestPVVoltage`

---

### 7.4 DeviceDetailScreen

Accessed from SummaryTab (directly for single device, via NavigationLink for multi-device).

**Toolbar:**
- Refresh button (clockwise arrow) — top-right
- Overflow/hamburger menu (3-line icon) — top-right, contains:
  - Export CSV
  - Device Settings (opens DeviceSettingsScreen as bottom sheet)
  - Rename (shows rename dialog)
  - Remove Device (destructive)

**Body:** `ScrollView` with:
1. Segmented time range picker: `1H | 6H | 24H | 3D`
2. Stats grid (2 columns, `LazyVerticalGrid`):

| Title | Value | Icon | Colour |
|---|---|---|---|
| Temperature | `58.3°C` | thermometer | dynamic (see tempColor) |
| State | e.g. `Solar Heating` | state icon | Orange |
| PV Voltage | `342 V` | sun.max | Orange |
| MPPT Voltage | `380 V` | bolt.circle | Blue |
| Solar Active | `2h 15m` | sun.max | Orange |
| Element Active | `0m` | bolt | Red |
| Idle / Off | `5h 45m` | moon | Blue |
| Solar (Week) | `7h 0m` | sun.max | Orange |
| Element (Week) | `3h 0m` | bolt | Red |
| Savings (Week) | `R 23.50` | banknote | Green |

**StatCard layout:**
```
[icon (caption2)] [Title (caption2, secondary)]
[Value (footnote.bold, tinted)]
```
- Background: `.quaternary` equivalent (Material: `Surface` with slight elevation or `surfaceVariant`)
- Corner radius: 16 dp
- `minimumScaleFactor` for value text (shrink to fit)

**tempColor:** ≥65°C → Red, ≤30°C → Blue, else Orange

**Duration format (`formatDuration`):**
- If hours > 0: `"Xh Ym"` (e.g. `"2h 15m"`)
- Else: `"Ym"` (e.g. `"45m"`)

**stateTotals logic:**
```kotlin
// Iterate filteredHistory pairs [i, i+1]
// dt = history[i+1].timestamp - history[i].timestamp (seconds)
// accumulate dt into solar/element/idle based on history[i].state
```

**filteredHistory:** All history points with `timestamp >= now - selectedRange.durationSeconds`

**CSV export columns:** `Timestamp,Temperature (°C),MPPT Voltage (V),PV On,AC On,State`
- Timestamp: ISO 8601
- Temperature: 4 decimal places
- Save to cache dir; open with Android `FileProvider` + `ACTION_SEND`/`ACTION_CREATE_DOCUMENT`

**Rename dialog:** AlertDialog with single TextField, pre-filled with current `friendlyName`. Save updates SharedPreferences `gg_manual_devices`.

---

### 7.5 GraphsTab (EnergyView equivalent)

**Top-level tab.**

**Empty state** (no devices): icon, "No Devices", "Add a device on the Summary tab first."

**Content (has devices):**
1. Segmented device picker — only shown if 2+ devices
2. Segmented time range picker: `1H | 6H | 24H | 3D`
3. On load / range change / device change: call `getPerformanceHistory`; show `CircularProgressIndicator` while loading
4. Charts (see Section 8 for chart specs):
   - Temperature chart (with tap-to-inspect)
   - PV Active chart (binary)
   - AC Active chart (binary)
   - PV Voltage chart
   - MPPT Voltage chart

**Toolbar:**
- Refresh button (clockwise arrow)
- Export CSV button (share icon) — disabled when no data

**Pull-to-refresh** supported.

**On device change:** reload data automatically.
**On time range change:** re-filter existing data (no new network call needed — full history already loaded).

---

### 7.6 SettingsTab (DeviceSettingsView embedded)

**Purpose:** Device configuration (temperature setpoints + operating mode).

**If 2+ devices:** show a segmented picker at top to select which device to configure.

**Content:** Embed `DeviceSettingsContent` directly (no navigation needed).

**Sections:**

#### Temperature Section
```
AC Setpoint       [Slider 30–80°C]    60°C
PV Setpoint       [Slider 30–90°C]    70°C
```
- AC range: 30–80°C (integer steps)
- PV range: 30–90°C, **minimum = acSetpoint** (enforce in slider)
- Sliders update local state only; not saved until user taps Save

#### Mode Section (RadioGroup or SegmentedControl)
```
○ Off
○ Solar Only
● Solar + Element   ← default
○ Element Only
```

#### Save Button
- Full-width, green, "Save Settings"
- Disabled when: `isLoading || !hasControl`
- On tap:
  1. Call `POST /app/setTemperature` with acMax, pvMax
  2. Call `POST /app/setMode` with geckoMode (only if mode changed from loaded value)
  3. On success: save to SharedPreferences; show success toast/snackbar
  4. On failure: show error snackbar

#### Control Warning Banner
Shown when `hasControl == false`:
```
⚠  Your account does not have control rights for this device.
```

**On load:**
1. `GET /app/hasControl` → set `hasControl`
2. Load saved `PersistedSettings` from SharedPreferences (keyed by GSN)
3. Populate sliders and mode selector from saved values

---

### 7.7 ProfileTab

**Content:**
- Account icon (large, centred) — `person.crop.circle` equivalent
- Email address (body, centred)
- Role badge (e.g. `OWNER`, `USER`, `VIEWER`)
- "Sign Out" button (red/destructive, full-width)

**Sign out flow:**
1. Show confirmation dialog: "Are you sure you want to sign out?"
2. On confirm: call `POST /auth/logout`
3. Clear all SharedPreferences + EncryptedSharedPreferences
4. Navigate to LoginScreen (clear back stack)

---

### 7.8 AddDeviceSheet (Bottom Sheet)

**Triggered by:** + button in SummaryTab toolbar.

**Fields:**
- GSN input (TextField, all-caps hint "e.g. GG-1234-AB")
  - On input: lowercase, strip colons: `gsn.lowercase().replace(":", "")`
- Device name (TextField, optional, placeholder "My Geyser")

**Add button flow:**
1. Validate GSN not empty.
2. Call `GET /app/getPerformanceHistory?gsn=<gsn>&token=<token>` — if it returns data, the GSN is valid.
3. On success: create `GeckoDevice(gsn, friendlyName)`, add to list, save to SharedPreferences, dismiss sheet.
4. On error (404 / empty): show inline error "Device not found. Check the GSN on your unit's screen."
5. Show progress indicator while verifying.

---

## 8. Chart Specifications

All charts display data filtered to the selected `TimeRange`. All timestamps shown in **SAST (UTC+2), 24-hour format**.

### 8.1 X-Axis Formatting

| TimeRange | X-axis format | Tick stride |
|---|---|---|
| 1H | `HH:mm` | every 15 minutes |
| 6H | `HH:mm` | every 1 hour |
| 24H | `HH:mm` | every 6 hours |
| 3D | `EEE HH:mm` (e.g. `Mon 14:00`) | every 1 day |

Locale must be `en_US_POSIX` (or equivalent) to force `HH` = 24-hour.

Timezone for all axis labels: `Africa/Johannesburg` (SAST, UTC+2, no DST).

### 8.2 Temperature Chart

- **Type:** Line chart
- **Colour:** Orange
- **Line width:** 2 dp
- **Y-axis:** Dynamic domain rounded outward to nearest 5°C with ±10°C padding
  ```kotlin
  val yMin = floor((temps.min() - 10) / 5) * 5
  val yMax = ceil((temps.max() + 10) / 5) * 5
  ```
- **Y-axis ticks:** Every 5°C
- **Y-axis labels:** `"60°"`, `"65°"`, etc. (left side)
- **Height:** ~200 dp
- **Tap-to-inspect:** On tap, show vertical dashed rule at nearest data point; show reading in chart header:
  ```
  Temperature            58.3°C
                         14:35
  ```
  When nothing selected, header shows: `N readings` (count)

### 8.3 PV Active Chart (binary)

- **Type:** Step chart — filled rectangles + step line
- **Colour:** Orange (fill at 35% opacity, line solid)
- **Y-axis:** Fixed domain 0–1; labels "Off" (0) and "On" (1)
- **Data:** `pvOn` field (0 or 1) per HistoryPoint
- **Height:** ~100 dp
- **Rendering:** For each point i, draw a rectangle from `data[i-1].timestamp` to `data[i].timestamp` with height = `data[i].pvOn`; draw step line at same positions

### 8.4 AC Active Chart (binary)

- Same spec as PV Active, but:
- **Colour:** Red (fill at 35% opacity, line solid)
- **Data:** `acOn` field

### 8.5 PV Voltage Chart

- **Type:** Line chart
- **Colour:** Orange
- **Unit:** "V" (shown on Y-axis labels: `"342 V"`)
- **Data:** `pvVoltage` (Int?) — skip points where value is null or 0
- **Height:** ~150 dp
- Empty state: "No PV voltage data in this range" (caption, centred)

### 8.6 MPPT Voltage Chart

- **Type:** Line chart
- **Colour:** Blue
- **Unit:** "V"
- **Data:** `mpptVoltage` (Int?) — skip points where value is null or 0
- **Height:** ~150 dp
- Empty state: "No MPPT voltage data in this range"

### 8.7 Chart Card Container

Each chart is wrapped in a card:
- Background: `surfaceVariant` or equivalent lightly-tinted surface
- Corner radius: 16 dp
- Padding: 16 dp inside
- Chart title: `Headline` / `titleMedium` style, left-aligned

---

## 9. Business Logic

### Adding a Device

1. User enters GSN (normalised: lowercase, colons removed).
2. Verify by calling `getPerformanceHistory` — success = device exists.
3. Add `GeckoDevice` to in-memory list.
4. Persist full device list to SharedPreferences (`gg_manual_devices`).
5. Navigate away / dismiss sheet.

### Removing a Device

1. Remove from in-memory list.
2. Persist updated list to SharedPreferences.
3. Optionally also delete `device_settings_<gsn>` key.

### Renaming a Device

1. Update `friendlyName` in the `GeckoDevice` entry.
2. Persist updated device list to SharedPreferences.

### Settings Save Flow

```kotlin
suspend fun saveSettings(gsn: String, acMax: Int, pvMax: Int, mode: String) {
    // 1. Validate pvMax >= acMax (enforce in UI too)
    require(pvMax >= acMax) { "PV setpoint must be ≥ AC setpoint" }

    // 2. Save temperature (always)
    api.setTemperature(token, gsn, acMax, pvMax)

    // 3. Save mode (only if changed from loaded value)
    if (mode != serverMode) {
        api.setMode(token, gsn, mode)
    }

    // 4. Persist locally
    val settings = PersistedSettings(acMax, pvMax, mode)
    prefs.edit().putString("device_settings_$gsn", gson.toJson(settings)).apply()
}
```

### Dashboard Refresh (multi-device)

Fetch in parallel for all devices; update each device's row independently as results come in.

### Data Filtering

`filteredHistory` = all history points where `timestamp >= Instant.now() - selectedRange.durationSeconds`

This filter is applied client-side to the full loaded dataset. No second network call needed when changing time range.

### Duration Formatting

```kotlin
fun formatDuration(seconds: Double): String {
    val h = seconds.toInt() / 3600
    val m = (seconds.toInt() % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
```

### Temperature Colour

```kotlin
fun tempColor(temp: Double): Color = when {
    temp >= 65.0 -> Red
    temp <= 30.0 -> Blue
    else         -> Orange
}
```

---

## 10. Error Handling

| Scenario | Handling |
|---|---|
| 401 on any call | Auto re-login (see Section 2); if re-login fails → logout + LoginScreen |
| Network timeout | Show error state in affected screen; retry button |
| Invalid GSN on add | Inline error below GSN field |
| `hasControl == false` | Show warning banner; disable all settings controls |
| `pvMax < acMax` | Enforce in slider (clamp PV minimum to AC value); show inline validation |
| Empty history | Show `ContentUnavailableView` equivalent: "No Data — No readings in the selected time range." |
| Server error (5xx) | Show error state with `error.localizedMessage`; retry button |

---

## 11. Timezone Handling

All timestamps from the server include a `+02:00` offset (SAST). Parse with:

```kotlin
// Use java.time (API 26+) or ThreeTenABP for older APIs
val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
val zdt = ZonedDateTime.parse(rawString, formatter)
val instant = zdt.toInstant()
```

For display on chart axes, always format in `Africa/Johannesburg`:

```kotlin
val sast = ZoneId.of("Africa/Johannesburg")
val displayFormatter = DateTimeFormatter
    .ofPattern("HH:mm", Locale.US)       // or "EEE HH:mm" for 3D range
    .withZone(sast)

val label = displayFormatter.format(instant)
```

> `Locale.US` is required to force 24-hour `HH` format regardless of the user's device locale.

---

## 12. App-Wide State

Implement as a singleton `AppViewModel` (or `AppRepository`) accessible across all screens.

```kotlin
@Singleton
class AppState @Inject constructor(
    private val prefs: SharedPreferences,
    private val encryptedPrefs: SharedPreferences,
    private val api: GeckoApiService
) {
    // Session
    var sessionToken: String? = null
    var deviceID: String? = null
    var userEmail: String? = null
    var userRole: String? = null

    // Device list (persisted)
    val devices: MutableStateFlow<List<GeckoDevice>> = MutableStateFlow(emptyList())

    fun loadDevices() { /* load from prefs */ }
    fun addDevice(device: GeckoDevice) { /* add + persist */ }
    fun removeDevice(gsn: String) { /* remove + persist */ }
    fun renameDevice(gsn: String, name: String) { /* update + persist */ }

    suspend fun login(email: String, password: String): Result<Unit> { /* ... */ }
    suspend fun logout() { /* ... */ }
    suspend fun restoreSession(): Boolean { /* ... */ }
}
```

---

## Appendix A: Colour Palette

| Name | Usage | Hex |
|---|---|---|
| Green | Primary tint, buttons, tab indicator | `#4CAF50` (Material Green 500) |
| Orange | Solar/temperature, chart lines | `#FF9800` (Material Orange 500) |
| Red | Element heating, destructive actions | `#F44336` (Material Red 500) |
| Blue | MPPT, idle state, chart lines | `#2196F3` (Material Blue 500) |
| Green (savings) | Savings stat card | `#4CAF50` |

---

## Appendix B: API Quick Reference

```
Base URL:  https://geckows.co.za
Static app token: b0f6fab1e44009644b6a7d7858741316  (Authorization header on /auth/* and /app/deviceid)
Session token:    from /auth/signin response .token  (Authorization header on data calls)

Headers (every request):
  Authorization: Bearer <static OR session token — see token table>
  X-IOS-App-Version: 17        (or X-Android-App-Version: 17)
  Content-Type: application/json

Tokens go in the Authorization HEADER, never in the body. Bodies/params use
"geckoSerialNumber" (not "gsn"). GSN is normalised lowercase, no colons (e.g. fce8c0de16e0).

POST /auth/checkuser        [static]  body: { email }
POST /auth/signin           [static]  body: { email, pw }            -> { xsrfToken, token, role } + Set-Cookie xsrf-token
POST /app/deviceid          [static]  body: {}                       -> { deviceID, role }
POST /auth/logout           [static]  body: {}
GET  /app/getPerformanceHistory [session]  ?geckoSerialNumber=
GET  /app/hasControl            [session]  ?geckoSerialNumber=
GET  /uv/uvindex                [session]  ?geckoSerialNumber=
POST /app/setTemperature    [session]  body: { geckoSerialNumber, acMax, pvMax }
POST /app/setMode           [session]  body: { geckoSerialNumber, geckoMode }
```

---

## Appendix C: SharedPreferences Summary

```
gg_token                   → String  (session token)
gg_deviceid                → String  (device ID from /app/deviceid)
gg_manual_devices          → JSON String (List<GeckoDevice>)
device_settings_<gsn>      → JSON String (PersistedSettings)

[EncryptedSharedPreferences]
gg_email                   → String
gg_password                → String
```
